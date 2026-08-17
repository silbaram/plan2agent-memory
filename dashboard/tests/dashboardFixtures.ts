import type { Page } from '@playwright/test'

export const artifactId = '33333333-3333-4333-8333-333333333333'
export const taskArtifactId = '44444444-4444-4444-8444-444444444444'
export const completedRunArtifactId = 'run-024-passed'
export const failedRunArtifactId = 'run-024-failed'
export const iterationId = '22222222-2222-4222-8222-222222222222'
export const projectId = '11111111-1111-4111-8111-111111111111'

export interface DashboardApiRequest {
  readonly body: string | null
  readonly headers: Readonly<Record<string, string>>
  readonly method: string
  readonly path: string
}

export interface DashboardApiMockOptions {
  readonly semanticSearchFailure?: 'embedding_provider_not_configured' | 'embedding_provider_unavailable'
  readonly traceTruncated?: boolean
}

interface MockApiResponse {
  readonly body: unknown
  readonly status: number
}

export async function mockDashboardApi(
  page: Page,
  requests: DashboardApiRequest[] = [],
  options: DashboardApiMockOptions = {},
) {
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    requests.push({
      body: request.postData(),
      headers: request.headers(),
      method: request.method(),
      path: `${url.pathname}${url.search}`,
    })

    const response = apiResponse(url, request.method(), options)
    await route.fulfill({
      body: JSON.stringify(response.body),
      contentType: 'application/json; charset=utf-8',
      status: response.status,
    })
  })
}

function apiResponse(url: URL, method: string, options: DashboardApiMockOptions): MockApiResponse {
  if (method === 'POST' && url.pathname === '/api/search/semantic' && options.semanticSearchFailure !== undefined) {
    return { body: { error: options.semanticSearchFailure }, status: 503 }
  }
  if (method !== 'GET') {
    return { body: page([]), status: 200 }
  }

  switch (url.pathname) {
    case '/api/projects':
      return { body: page([projectFixture()]), status: 200 }
    case `/api/projects/${projectId}/iterations`:
      return { body: page([iterationFixture()]), status: 200 }
    case '/api/artifacts':
      return { body: page([taskArtifactLookupFixture(), completedRunLookupFixture(), failedRunLookupFixture()]), status: 200 }
    case `/api/artifacts/DOCUMENT_SNAPSHOT/${artifactId}`:
      return { body: documentArtifactDetailFixture(), status: 200 }
    case `/api/artifacts/TASK/${taskArtifactId}`:
      return { body: taskArtifactDetailFixture(), status: 200 }
    case `/api/artifacts/RUN_RECORD/${completedRunArtifactId}`:
      return { body: runArtifactDetailFixture({ completed: true }), status: 200 }
    case `/api/artifacts/RUN_RECORD/${failedRunArtifactId}`:
      return { body: runArtifactDetailFixture({ completed: false }), status: 200 }
    case '/api/search/keyword':
      return { body: page([keywordSearchFixture()], 'search-next-page'), status: 200 }
    case '/api/graph/nodes':
      return { body: [taskNodeFixture()], status: 200 }
    case '/api/graph/trace':
      return { body: traceFixture(options.traceTruncated), status: 200 }
    case '/api/health':
      return { body: { status: 'UP', timestamp: '2026-07-26T00:00:00Z' }, status: 200 }
    default:
      return { body: { error: 'not_found' }, status: 404 }
  }
}

function page(items: readonly Record<string, unknown>[], nextCursor: string | null = null) {
  return { items, nextCursor }
}

function projectFixture() {
  return {
    canonicalServerId: 'server-1',
    createdAt: '2026-07-26T00:00:00Z',
    metadata: {},
    name: '예제 프로젝트',
    projectId,
    rootPath: '/projects/example',
    sourceProjectId: 'source-project',
    sourceReference: null,
    updatedAt: null,
  }
}

function iterationFixture() {
  return {
    createdAt: '2026-07-26T00:00:00Z',
    iterationId,
    label: '대시보드 검증',
    metadata: {},
    projectId,
    sourceIterationId: 'source-iteration',
    sourceReference: null,
    status: 'active',
    updatedAt: null,
  }
}

function taskArtifactLookupFixture() {
  return artifactLookupFixture({
    artifactId: taskArtifactId,
    artifactType: 'TASK',
    metadata: { status: 'in_progress' },
    taskId: 'task-024',
    title: '프로덕션 대시보드 검증',
  })
}

function completedRunLookupFixture() {
  return artifactLookupFixture({
    artifactId: completedRunArtifactId,
    artifactType: 'RUN_RECORD',
    metadata: {
      completedAt: '2026-07-26T01:00:00Z',
      result: 'passed',
      status: 'finished',
    },
    runId: 'run-024-passed',
    sourcePath: 'runs/run-024-passed.json',
    title: '완료된 검증 실행',
  })
}

function failedRunLookupFixture() {
  return artifactLookupFixture({
    artifactId: failedRunArtifactId,
    artifactType: 'RUN_RECORD',
    metadata: {
      completedAt: '2026-07-26T01:05:00Z',
      result: 'verification_failed',
      status: 'failed',
    },
    runId: 'run-024-failed',
    sourcePath: 'runs/run-024-failed.json',
    title: '실패한 검증 실행',
  })
}

function artifactLookupFixture(overrides: Readonly<Record<string, unknown>>) {
  const artifactType = overrides.artifactType ?? 'DOCUMENT_SNAPSHOT'
  const currentArtifactId = overrides.artifactId ?? artifactId
  const taskId = overrides.taskId ?? null
  const runId = overrides.runId ?? null
  const sourcePath = overrides.sourcePath ?? 'docs/dashboard.md'
  return {
    artifactId: currentArtifactId,
    artifactType,
    contentHash: null,
    createdAt: '2026-07-26T00:00:00Z',
    iterationId,
    lineage: {
      contentHash: null,
      iterationId,
      projectId,
      runId,
      snapshotVersion: null,
      sourcePath,
      taskId,
    },
    metadata: {},
    projectId,
    runId,
    snapshotVersion: null,
    sourceIds: sourceIds(),
    sourcePath,
    sourceReference: null,
    taskId,
    title: '프로덕션 대시보드 검증',
    updatedAt: null,
    ...overrides,
  }
}

function documentArtifactDetailFixture() {
  return artifactDetailFixture({
    artifactId,
    artifactType: 'DOCUMENT_SNAPSHOT',
    lineage: {
      artifactRefs: [artifactReference(completedRunArtifactId, 'RUN_RECORD', 'runs/run-024-passed.json')],
      contentHash: 'a'.repeat(64),
      documentId: 'document-1',
      iterationId,
      projectId,
      runId: null,
      snapshotVersion: 1,
      taskGraphId: 'graph-1',
      taskId: 'task-024',
    },
    mediaType: 'text/markdown',
    metadata: { phase: 'browser-verification', status: 'approved' },
    rawContent: '# 안전한 미리보기\n\n[공개 문서](https://example.com/docs)\n\n<script>window.__unsafe = true</script>',
    source: artifactSource({ sourceRunId: 'run-024-passed', sourceTaskId: 'task-024' }),
    title: '프로덕션 대시보드 검증',
  })
}

function taskArtifactDetailFixture() {
  return artifactDetailFixture({
    artifactId: taskArtifactId,
    artifactType: 'TASK',
    lineage: {
      artifactRefs: [
        artifactReference(completedRunArtifactId, 'RUN_RECORD', 'runs/run-024-passed.json'),
        artifactReference(failedRunArtifactId, 'RUN_RECORD', 'runs/run-024-failed.json'),
      ],
      contentHash: 'b'.repeat(64),
      documentId: artifactId,
      iterationId,
      projectId,
      runId: null,
      snapshotVersion: null,
      taskGraphId: 'graph-1',
      taskId: null,
    },
    mediaType: 'text/markdown',
    metadata: { status: 'in_progress' },
    rawContent: '# 프로덕션 대시보드 검증\n\n완료 및 실패한 검증 실행은 명시적인 계보로만 연결합니다.',
    source: artifactSource(),
    title: '프로덕션 대시보드 검증',
  })
}

function runArtifactDetailFixture({ completed }: { readonly completed: boolean }) {
  const currentArtifactId = completed ? completedRunArtifactId : failedRunArtifactId
  const runId = completed ? 'run-024-passed' : 'run-024-failed'
  return artifactDetailFixture({
    artifactId: currentArtifactId,
    artifactType: 'RUN_RECORD',
    lineage: {
      artifactRefs: [artifactReference(taskArtifactId, 'TASK', 'tasks/task-024.json')],
      contentHash: completed ? 'c'.repeat(64) : 'd'.repeat(64),
      documentId: null,
      iterationId,
      projectId,
      runId,
      snapshotVersion: null,
      taskGraphId: 'graph-1',
      taskId: 'task-024',
    },
    mediaType: 'application/json',
    metadata: { result: completed ? 'passed' : 'verification_failed', status: completed ? 'finished' : 'failed' },
    rawContent: JSON.stringify({ result: completed ? 'passed' : 'verification_failed', runId, taskId: 'task-024' }),
    source: artifactSource({ sourceRunId: runId, sourceTaskId: 'task-024' }),
    title: completed ? '완료된 검증 실행' : '실패한 검증 실행',
  })
}

function artifactDetailFixture(overrides: Readonly<Record<string, unknown>>) {
  return {
    artifactId,
    artifactType: 'DOCUMENT_SNAPSHOT',
    createdAt: '2026-07-26T00:00:00Z',
    iterationId,
    lineage: {
      artifactRefs: [],
      contentHash: 'a'.repeat(64),
      documentId: 'document-1',
      iterationId,
      projectId,
      runId: null,
      snapshotVersion: 1,
      taskGraphId: 'graph-1',
      taskId: 'task-024',
    },
    mediaType: 'text/plain',
    metadata: {},
    projectId,
    rawContent: 'Stored fixture content',
    source: artifactSource(),
    title: '프로덕션 대시보드 검증',
    updatedAt: null,
    ...overrides,
  }
}

function artifactReference(artifactId: string, artifactType: string, sourcePath: string) {
  return { artifactId, artifactType, sourcePath }
}

function artifactSource(overrides: Readonly<Record<string, unknown>> = {}) {
  return {
    sourceDocumentId: 'document-1',
    sourceIterationId: 'source-iteration',
    sourcePath: 'docs/dashboard.md',
    sourceProjectId: 'source-project',
    sourceReference: null,
    sourceRunId: null,
    sourceTaskGraphId: 'graph-1',
    sourceTaskId: null,
    ...overrides,
  }
}

function keywordSearchFixture() {
  const chunkId = 'chunk-1'
  const documentId = artifactId
  return {
    artifactType: 'DOCUMENT_CHUNK',
    chunkId,
    chunkIndex: 0,
    citation: {
      lineage: {
        chunkId,
        chunkIndex: 0,
        documentId,
        iterationId,
        projectId,
        sourcePath: 'docs/dashboard.md',
      },
      sourceIds: sourceIds(),
      sourceReference: null,
    },
    content: '프로덕션 검증을 위한 검색 결과입니다.',
    documentId,
    iterationId,
    lineage: {
      chunkId,
      chunkIndex: 0,
      documentId,
      iterationId,
      projectId,
      sourcePath: 'docs/dashboard.md',
    },
    matchReason: 'content',
    metadata: {},
    projectId,
    score: 0.987,
    sourceIds: sourceIds(),
    sourcePath: 'docs/dashboard.md',
    sourceReference: null,
  }
}

function taskNodeFixture() {
  return {
    content: null,
    documentId: null,
    iterationId,
    label: '대시보드 검증 작업',
    metadata: {},
    naturalKey: 'task:task-024',
    nodeId: 'node-task-024',
    nodeKind: 'TASK',
    projectId,
    runId: null,
    taskId: 'task-024',
  }
}

function documentNodeFixture() {
  return {
    content: null,
    documentId: artifactId,
    iterationId,
    label: '프로덕션 대시보드 검증',
    metadata: {},
    naturalKey: `document:${artifactId}`,
    nodeId: 'node-document',
    nodeKind: 'DOCUMENT',
    projectId,
    runId: null,
    taskId: null,
  }
}

function completedRunNodeFixture() {
  return {
    content: null,
    documentId: null,
    iterationId,
    label: '완료된 검증 실행',
    metadata: { completedAt: '2026-07-26T01:00:00Z', result: 'passed', status: 'finished' },
    naturalKey: 'run:run-024-passed',
    nodeId: 'node-run-completed',
    nodeKind: 'RUN',
    projectId,
    runId: 'run-024-passed',
    taskId: 'task-024',
  }
}

function traceFixture(truncated = false) {
  const root = taskNodeFixture()
  const documentNode = documentNodeFixture()
  const completedRunNode = completedRunNodeFixture()
  return {
    edges: [
      {
        edgeId: 'edge-task-document',
        edgeType: 'DERIVED_FROM',
        fromNodeId: root.nodeId,
        metadata: {},
        projectId,
        sourceReference: null,
        toNodeId: documentNode.nodeId,
      },
      {
        edgeId: 'edge-task-completed-run',
        edgeType: 'EXECUTED_FOR',
        fromNodeId: root.nodeId,
        metadata: {},
        projectId,
        sourceReference: null,
        toNodeId: completedRunNode.nodeId,
      },
    ],
    nodes: [
      { depth: 0, node: root },
      { depth: 1, node: documentNode },
      { depth: 1, node: completedRunNode },
    ],
    root,
    truncated,
  }
}

function sourceIds() {
  return {
    sourceChunkId: null,
    sourceDocumentId: null,
    sourceIterationId: 'source-iteration',
    sourceProjectId: null,
    sourceRunId: null,
    sourceTaskGraphId: null,
    sourceTaskId: 'task-024',
  }
}
