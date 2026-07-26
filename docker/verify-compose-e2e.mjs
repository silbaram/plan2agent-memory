import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'

const ONE_MEBIBYTE = 1024 * 1024
const MAX_UPSTREAM_RESPONSE_BYTES = 10 * ONE_MEBIBYTE
const HEALTH_WAIT_TIMEOUT_MILLISECONDS = 180_000
const PROVIDER_WAIT_TIMEOUT_MILLISECONDS = 30_000
const BACKEND_BUILDER_IMAGE = 'eclipse-temurin:21.0.11_10-jdk-jammy@sha256:9d8dcf999b0bce2453e913823595a5ff2a4e8e9e5d5241b45280d0ff069818ec'
const BACKEND_RUNTIME_IMAGE = 'eclipse-temurin:21.0.11_10-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13'
const DASHBOARD_IMAGE = 'node:22.23.1-bookworm-slim@sha256:6c74791e557ce11fc957704f6d4fe134a7bc8d6f5ca4403205b2966bd488f6b3'
const POSTGRES_IMAGE = 'pgvector/pgvector:0.8.5-pg17-bookworm@sha256:d2ef61f42ef767baa5a1475393303cc235bcd92febd9d7014eddb48b41f3bad0'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const repositoryDirectory = resolve(scriptDirectory, '..')
const composeFile = resolve(repositoryDirectory, 'compose.yaml')
const composeExecutable = process.env.P2A_COMPOSE_BIN ?? 'docker-compose'
const runId = randomUUID().slice(0, 8)
const projectName = `p2a-e2e-${runId}`
const modelDirectoryParent = process.env.P2A_E2E_MODEL_TMPDIR ?? process.env.HOME ?? tmpdir()
const modelDirectory = await mkdtemp(resolve(modelDirectoryParent, '.p2a-compose-e2e-model-'))
const emptyEnvironmentDirectory = await mkdtemp(resolve(tmpdir(), 'p2a-compose-e2e-env-'))
const emptyEnvironmentFile = resolve(emptyEnvironmentDirectory, 'empty.env')
const localToken = `p2a-e2e-${randomUUID()}`
const databasePassword = `p2a-e2e-${randomUUID()}`
const inferredLimaSocket = process.env.HOME === undefined
  ? undefined
  : resolve(process.env.HOME, '.lima', 'default', 'sock', 'docker.sock')
const dockerHost = process.env.P2A_DOCKER_HOST
  ?? (inferredLimaSocket !== undefined && existsSync(inferredLimaSocket) ? `unix://${inferredLimaSocket}` : process.env.DOCKER_HOST)
const composeEnvironment = {
  ...process.env,
  ...(dockerHost === undefined ? {} : { DOCKER_HOST: dockerHost }),
  P2A_BACKEND_HOST_PORT: '0',
  P2A_DASHBOARD_HOST_PORT: '0',
  P2A_DB_PASSWORD: databasePassword,
  P2A_LOCAL_TOKEN: localToken,
  P2A_MODEL_DIR: modelDirectory,
  P2A_POSTGRES_CONTAINER_NAME: `${projectName}-postgres`,
  P2A_POSTGRES_HOST_PORT: '0',
}

let composeStarted = false
let fixture
let taskError

try {
  await writeFile(resolve(modelDirectory, 'model.onnx'), 'intentionally-invalid-e2e-model\n')
  await writeFile(resolve(modelDirectory, 'tokenizer.json'), '{}\n')
  await writeFile(emptyEnvironmentFile, '')

  await verifyPinnedImageDefinitions()
  docker(['version', '--format', '{{.Server.Version}}'])
  compose(['config', '--quiet'])
  composeStarted = true
  compose(['up', '--build', '--detach', '--wait', '--wait-timeout', '180'])

  for (const service of ['postgres', 'backend', 'dashboard']) {
    assertServiceHealthy(service)
  }

  const backendOrigin = publishedOrigin('backend', 8080)
  let dashboardOrigin = publishedOrigin('dashboard', 4173)

  await expectJson(`${dashboardOrigin}/healthz`, 200, { status: 'ok' })
  await expectJson(`${backendOrigin}/actuator/health`, 200, { status: 'UP' })
  await seedFixture(backendOrigin)
  await verifyBffReadFlows(dashboardOrigin)
  await verifyProviderDegradedError(dashboardOrigin, backendOrigin)
  await verifyLargeUpstreamResponse(dashboardOrigin, backendOrigin)
  await verifyBackendDownError(dashboardOrigin)
  dashboardOrigin = await verifyRestartAndPersistence(dashboardOrigin)
  dashboardOrigin = await verifyTimeoutError()
  verifyContainerInvariants()

  console.log(`Compose E2E passed for isolated project ${projectName}`)
} catch (error) {
  taskError = error
  throw error
} finally {
  let cleanupError
  if (composeStarted) {
    try {
      compose(['down', '--remove-orphans'])
    } catch (error) {
      cleanupError = error
    }
    try {
      docker(['volume', 'rm', `${projectName}_p2a-artifact-store-postgres-data`])
    } catch (error) {
      if (!String(error).includes('No such volume')) {
        cleanupError ??= error
      }
    }
  }
  await rm(modelDirectory, { force: true, recursive: true })
  await rm(emptyEnvironmentDirectory, { force: true, recursive: true })
  if (cleanupError !== undefined) {
    if (taskError === undefined) {
      throw cleanupError
    }
    console.error(`Isolated Compose cleanup failed: ${cleanupError.message}`)
  }
}

function compose(argumentsList) {
  return run(composeExecutable, [
    '--env-file',
    emptyEnvironmentFile,
    '--file',
    composeFile,
    '--project-name',
    projectName,
    ...argumentsList,
  ], composeEnvironment)
}

function docker(argumentsList) {
  return run('docker', argumentsList, composeEnvironment)
}

function run(command, argumentsList, environment = process.env) {
  try {
    return execFileSync(command, argumentsList, {
      encoding: 'utf8',
      env: environment,
      stdio: ['ignore', 'pipe', 'pipe'],
    }).trim()
  } catch (error) {
    const stdout = error.stdout?.toString() ?? ''
    const stderr = error.stderr?.toString() ?? ''
    throw new Error(`${command} ${argumentsList.join(' ')} failed\n${stdout}${stderr}`)
  }
}

function assertServiceHealthy(service) {
  const containerId = compose(['ps', '--quiet', service])
  if (!containerId) {
    throw new Error(`Compose service ${service} did not create a container`)
  }

  const status = docker([
    'inspect',
    '--format',
    '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}',
    containerId,
  ])
  if (status !== 'healthy') {
    throw new Error(`Compose service ${service} is not healthy: ${status}`)
  }
}

function publishedOrigin(service, containerPort) {
  const address = compose(['port', service, String(containerPort)])
  if (!address.startsWith('127.0.0.1:')) {
    throw new Error(`${service}:${containerPort} is not published on 127.0.0.1: ${address}`)
  }
  return `http://${address}`
}

async function verifyPinnedImageDefinitions() {
  const [composeSource, backendDockerfile, dashboardDockerfile] = await Promise.all([
    readFile(composeFile, 'utf8'),
    readFile(resolve(repositoryDirectory, 'Dockerfile'), 'utf8'),
    readFile(resolve(repositoryDirectory, 'dashboard', 'Dockerfile'), 'utf8'),
  ])
  for (const [label, source, requiredImage] of [
    ['compose', composeSource, POSTGRES_IMAGE],
    ['backend Dockerfile', backendDockerfile, BACKEND_BUILDER_IMAGE],
    ['backend Dockerfile', backendDockerfile, BACKEND_RUNTIME_IMAGE],
    ['dashboard Dockerfile', dashboardDockerfile, DASHBOARD_IMAGE],
  ]) {
    if (!source.includes(requiredImage)) {
      throw new Error(`${label} does not retain required digest ${requiredImage}`)
    }
  }
  for (const [label, source] of [
    ['compose', composeSource],
    ['backend Dockerfile', backendDockerfile],
    ['dashboard Dockerfile', dashboardDockerfile],
  ]) {
    if (/^\s*(platform:|--platform=)/m.test(source)) {
      throw new Error(`${label} hard-codes a container platform`)
    }
  }
}

async function seedFixture(backendOrigin) {
  const ids = fixtureIds()
  fixture = ids

  await postBackend(backendOrigin, '/api/projects', {
    projectId: ids.projectId,
    sourceProjectId: 'compose-e2e-project',
    name: 'Compose E2E Project',
    canonicalServerId: ids.projectId,
    rootPath: '/compose-e2e',
    metadata: {},
  })
  await postBackend(backendOrigin, `/api/projects/${ids.projectId}/iterations`, {
    iterationId: ids.iterationId,
    sourceIterationId: 'compose-e2e-iteration',
    label: 'Compose E2E',
    status: 'ACTIVE',
    metadata: {},
  })
  await postBackend(backendOrigin, '/api/documents/snapshots', documentSnapshot(ids))
  await postBackend(backendOrigin, '/api/task-graphs', {
    taskGraphId: ids.taskGraphId,
    projectId: ids.projectId,
    iterationId: ids.iterationId,
    sourceTaskGraphId: 'compose-e2e-graph',
    graphHash: 'compose-e2e-graph-hash',
    graphJson: JSON.stringify({ tasks: [ids.taskId] }),
    taskIds: [ids.taskId],
    dependencyEdges: [],
    metadata: {},
  })
  await postBackend(backendOrigin, '/api/tasks/bulk', {
    graphId: ids.taskGraphId,
    tasks: [{
      taskId: ids.taskId,
      projectId: ids.projectId,
      iterationId: ids.iterationId,
      taskGraphId: ids.taskGraphId,
      sourceTaskId: 'compose-e2e-task',
      title: 'Compose E2E task',
      description: 'BFF round-trip fixture',
      status: 'READY',
      targetArea: 'docker/compose-e2e',
      dependencies: [],
      acceptanceCriteria: ['Exercise BFF read routes'],
      metadata: {},
    }],
  })
  await postBackend(backendOrigin, '/api/document-chunks/bulk', {
    documentId: ids.documentId,
    chunks: [{
      chunk: {
        chunkId: ids.chunkId,
        projectId: ids.projectId,
        iterationId: ids.iterationId,
        taskId: ids.taskId,
        artifactType: 'DOCUMENT_SNAPSHOT',
        sourcePath: 'compose-e2e.md',
        chunkIndex: 0,
        content: 'compose-e2e-keyword proves BFF keyword search traversal',
        chunkHash: 'compose-e2e-chunk-hash',
        metadata: {},
      },
    }],
  })
  await postBackend(backendOrigin, '/api/graph/snapshots', {
    projectId: ids.projectId,
    iterationId: ids.iterationId,
    nodes: [{
      nodeId: ids.graphNodeId,
      nodeKind: 'decision',
      naturalKey: ids.naturalKey,
      label: 'Compose E2E decision',
      content: 'Graph route fixture',
      metadata: {},
    }],
    edges: [],
  })
}

async function verifyBffReadFlows(dashboardOrigin) {
  const ids = fixture
  await expectJson(`${dashboardOrigin}/api/projects`, 200, { projectId: ids.projectId })
  await expectJson(`${dashboardOrigin}/api/projects/${ids.projectId}/iterations`, 200, { iterationId: ids.iterationId })
  await expectJson(`${dashboardOrigin}/api/artifacts/DOCUMENT_SNAPSHOT/${ids.documentId}`, 200, { documentId: ids.documentId })
  await expectJson(`${dashboardOrigin}/api/search/keyword?q=compose-e2e-keyword`, 200, { sourcePath: 'compose-e2e.md' })
  await expectJson(`${dashboardOrigin}/api/graph/nodes?projectId=${ids.projectId}`, 200, { naturalKey: ids.naturalKey })
  await expectJson(
    `${dashboardOrigin}/api/graph/trace?projectId=${ids.projectId}&naturalKey=${encodeURIComponent(ids.naturalKey)}`,
    200,
    { naturalKey: ids.naturalKey },
  )
}

async function verifyProviderDegradedError(dashboardOrigin, backendOrigin) {
  await waitFor(async () => {
    const response = await fetch(`${dashboardOrigin}/api/search/semantic`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ q: 'compose-e2e-keyword' }),
    })
    const body = await response.text()
    if (response.status !== 503 || !body.includes('embedding_provider_unavailable')) {
      throw new Error(`Provider is not degraded yet: HTTP ${response.status} ${body}`)
    }
    assertSecretSafe(body)
  }, PROVIDER_WAIT_TIMEOUT_MILLISECONDS)
  await expectJson(`${backendOrigin}/actuator/health`, 200, { status: 'UP' })
}

async function verifyLargeUpstreamResponse(dashboardOrigin, backendOrigin) {
  const ids = fixture
  const largeDocumentId = randomUUID()
  const response = await fetch(`${backendOrigin}/api/documents/snapshots`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-p2a-local-token': localToken,
    },
    body: JSON.stringify(documentSnapshot({
      ...ids,
      documentId: largeDocumentId,
      sourceDocumentId: 'compose-e2e-large-document',
      sourcePath: 'compose-e2e-large.md',
      content: 'x'.repeat(MAX_UPSTREAM_RESPONSE_BYTES + ONE_MEBIBYTE),
    })),
  })
  if (response.status !== 201) {
    throw new Error(`Unable to create 10 MiB fixture: HTTP ${response.status} ${await response.text()}`)
  }

  const oversizedResponse = await fetch(`${dashboardOrigin}/api/artifacts/DOCUMENT_SNAPSHOT/${largeDocumentId}`)
  const body = await oversizedResponse.text()
  if (oversizedResponse.status !== 502 || !body.includes('bff_response_too_large')) {
    throw new Error(`Expected 10 MiB safe BFF error, received HTTP ${oversizedResponse.status} ${body}`)
  }
  assertSecretSafe(body)
}

async function verifyBackendDownError(dashboardOrigin) {
  compose(['stop', 'backend'])
  const response = await fetch(`${dashboardOrigin}/api/projects`)
  const body = await response.text()
  if (response.status !== 503 || !body.includes('bff_backend_unavailable')) {
    throw new Error(`Expected backend-down safe BFF error, received HTTP ${response.status} ${body}`)
  }
  assertSecretSafe(body)
  compose(['start', 'backend'])
  await waitFor(() => assertServiceHealthy('backend'), HEALTH_WAIT_TIMEOUT_MILLISECONDS)
}

async function verifyRestartAndPersistence(dashboardOrigin) {
  compose(['stop'])
  compose(['up', '--detach', '--wait', '--wait-timeout', '180'])
  for (const service of ['postgres', 'backend', 'dashboard']) {
    assertServiceHealthy(service)
  }
  const restartedDashboardOrigin = publishedOrigin('dashboard', 4173)
  await expectJson(`${restartedDashboardOrigin}/api/projects`, 200, { projectId: fixture.projectId })
  return restartedDashboardOrigin
}

async function verifyTimeoutError() {
  const dashboardContainer = compose(['ps', '--quiet', 'dashboard'])
  const dashboardImage = docker(['inspect', '--format', '{{.Image}}', dashboardContainer])
  const slowContainerName = `${projectName}-slow-upstream`
  const slowUpstreamOrigin = 'http://slow-upstream:8787'

  docker([
    'run',
    '--detach',
    '--rm',
    '--name',
    slowContainerName,
    '--network',
    `${projectName}_default`,
    '--network-alias',
    'slow-upstream',
    dashboardImage,
    'node',
    '-e',
    "require('node:http').createServer(() => {}).listen(8787, '0.0.0.0')",
  ])

  try {
    composeEnvironment.P2A_BFF_UPSTREAM_ORIGIN = slowUpstreamOrigin
    compose(['up', '--detach', '--force-recreate', 'dashboard'])
    await waitFor(() => assertServiceHealthy('dashboard'), HEALTH_WAIT_TIMEOUT_MILLISECONDS)

    const timeoutOrigin = publishedOrigin('dashboard', 4173)
    const response = await fetch(`${timeoutOrigin}/api/projects`, { signal: AbortSignal.timeout(20_000) })
    const body = await response.text()
    if (response.status !== 504 || !body.includes('bff_timeout')) {
      throw new Error(`Expected timeout safe BFF error, received HTTP ${response.status} ${body}`)
    }
    assertSecretSafe(body)
  } finally {
    composeEnvironment.P2A_BFF_UPSTREAM_ORIGIN = 'http://backend:8080'
    compose(['up', '--detach', '--force-recreate', 'dashboard'])
    await waitFor(() => assertServiceHealthy('dashboard'), HEALTH_WAIT_TIMEOUT_MILLISECONDS)
    docker(['rm', '--force', slowContainerName])
  }

  return publishedOrigin('dashboard', 4173)
}

function verifyContainerInvariants() {
  const backendContainer = compose(['ps', '--quiet', 'backend'])
  const dashboardContainer = compose(['ps', '--quiet', 'dashboard'])
  const backendUser = docker(['inspect', '--format', '{{.Config.User}}', backendContainer])
  const dashboardUser = docker(['inspect', '--format', '{{.Config.User}}', dashboardContainer])
  const backendArchitecture = docker(['inspect', '--format', '{{.Architecture}}', backendContainer])
  const dashboardArchitecture = docker(['inspect', '--format', '{{.Architecture}}', dashboardContainer])
  const hostArchitecture = docker(['version', '--format', '{{.Server.Arch}}'])
  const modelMount = docker(['inspect', '--format', '{{range .Mounts}}{{if eq .Destination "/opt/p2a/model"}}{{.RW}}{{end}}{{end}}', backendContainer])

  if (backendUser !== '10001:10001') {
    throw new Error(`Backend does not run as UID/GID 10001: ${backendUser}`)
  }
  if (dashboardUser !== 'node') {
    throw new Error(`Dashboard does not run as node: ${dashboardUser}`)
  }
  if (backendArchitecture !== hostArchitecture || dashboardArchitecture !== hostArchitecture) {
    throw new Error(`Container architecture differs from Docker server: ${backendArchitecture}, ${dashboardArchitecture}, ${hostArchitecture}`)
  }
  if (modelMount !== 'false') {
    throw new Error('Backend model mount is not read-only')
  }
}

async function postBackend(backendOrigin, path, body) {
  const response = await fetch(`${backendOrigin}${path}`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-p2a-local-token': localToken,
    },
    body: JSON.stringify(body),
  })
  if (response.status !== 201) {
    throw new Error(`POST ${path} failed: HTTP ${response.status} ${await response.text()}`)
  }
}

async function expectJson(url, expectedStatus, expectedFields) {
  const response = await fetch(url)
  const body = await response.text()
  if (response.status !== expectedStatus) {
    throw new Error(`Expected HTTP ${expectedStatus} from ${url}, received ${response.status}: ${body}`)
  }
  for (const [key, value] of Object.entries(expectedFields)) {
    if (!body.includes(JSON.stringify(value))) {
      throw new Error(`Response from ${url} does not contain ${key}: ${body}`)
    }
  }
}

async function waitFor(assertion, timeoutMilliseconds) {
  const deadline = Date.now() + timeoutMilliseconds
  let lastError
  while (Date.now() < deadline) {
    try {
      await assertion()
      return
    } catch (error) {
      lastError = error
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 1_000))
    }
  }
  throw lastError ?? new Error(`Timed out after ${timeoutMilliseconds} ms`)
}

function assertSecretSafe(body) {
  if (body.includes(localToken) || body.includes(databasePassword)) {
    throw new Error('BFF error leaked a local secret')
  }
}

function fixtureIds() {
  return {
    chunkId: randomUUID(),
    documentId: randomUUID(),
    graphNodeId: randomUUID(),
    iterationId: randomUUID(),
    naturalKey: 'decision:compose-e2e',
    projectId: randomUUID(),
    sourceDocumentId: 'compose-e2e-document',
    sourcePath: 'compose-e2e.md',
    taskGraphId: randomUUID(),
    taskId: randomUUID(),
  }
}

function documentSnapshot(ids) {
  return {
    documentId: ids.documentId,
    projectId: ids.projectId,
    iterationId: ids.iterationId,
    sourceDocumentId: ids.sourceDocumentId,
    sourcePath: ids.sourcePath,
    snapshotVersion: 1,
    artifactType: 'DOCUMENT_SNAPSHOT',
    title: 'Compose E2E document',
    content: ids.content ?? 'Compose E2E artifact detail fixture',
    contentHash: `compose-e2e-${ids.documentId}`,
    metadata: {},
  }
}
