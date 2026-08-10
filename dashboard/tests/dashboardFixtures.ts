import type { Page } from '@playwright/test'

export const artifactId = '33333333-3333-4333-8333-333333333333'
export const iterationId = '22222222-2222-4222-8222-222222222222'
export const projectId = '11111111-1111-4111-8111-111111111111'

export interface DashboardApiRequest {
  readonly headers: Readonly<Record<string, string>>
  readonly method: string
  readonly path: string
}

export interface DashboardApiMockOptions {
  readonly traceTruncated?: boolean
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
      headers: request.headers(),
      method: request.method(),
      path: `${url.pathname}${url.search}`,
    })

    await route.fulfill({
      body: JSON.stringify(apiResponse(url, request.method(), options)),
      contentType: 'application/json; charset=utf-8',
    })
  })
}

function apiResponse(url: URL, method: string, options: DashboardApiMockOptions): unknown {
  if (method !== 'GET') {
    return { items: [], nextCursor: null }
  }

  switch (url.pathname) {
    case '/api/projects':
      return page([projectFixture()])
    case `/api/projects/${projectId}/iterations`:
      return page([iterationFixture()])
    case '/api/artifacts':
      return page([artifactLookupFixture()])
    case `/api/artifacts/DOCUMENT_SNAPSHOT/${artifactId}`:
      return artifactDetailFixture('DOCUMENT_SNAPSHOT')
    case '/api/search/keyword':
      return page([keywordSearchFixture()])
    case '/api/graph/nodes':
      return [taskNodeFixture()]
    case '/api/graph/trace':
      return traceFixture(options.traceTruncated)
    case '/api/health':
      return { status: 'UP', timestamp: '2026-07-26T00:00:00Z' }
    default:
      return { error: 'not_found' }
  }
}

function page(items: readonly Record<string, unknown>[]) {
  return { items, nextCursor: null }
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

function artifactLookupFixture() {
  return {
    artifactId,
    artifactType: 'TASK',
    contentHash: null,
    createdAt: '2026-07-26T00:00:00Z',
    iterationId,
    lineage: {
      contentHash: null,
      iterationId,
      projectId,
      runId: null,
      snapshotVersion: null,
      sourcePath: 'docs/dashboard.md',
      taskId: 'task-024',
    },
    metadata: {},
    projectId,
    runId: null,
    snapshotVersion: null,
    sourceIds: sourceIds(),
    sourcePath: 'docs/dashboard.md',
    sourceReference: null,
    taskId: 'task-024',
    title: '프로덕션 대시보드 검증',
    updatedAt: null,
  }
}

function artifactDetailFixture(artifactType: 'DOCUMENT_SNAPSHOT') {
  return {
    artifactId,
    artifactType,
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
    mediaType: 'text/markdown',
    metadata: { phase: 'browser-verification' },
    projectId,
    rawContent: '# 안전한 미리보기\n\n[공개 문서](https://example.com/docs)\n\n<script>window.__unsafe = true</script>',
    source: {
      sourceDocumentId: 'document-1',
      sourceIterationId: 'source-iteration',
      sourcePath: 'docs/dashboard.md',
      sourceProjectId: 'source-project',
      sourceReference: null,
      sourceRunId: null,
      sourceTaskGraphId: 'graph-1',
      sourceTaskId: 'task-024',
    },
    title: '프로덕션 대시보드 검증',
    updatedAt: null,
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
    score: 1,
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

function traceFixture(truncated = false) {
  const root = taskNodeFixture()
  const documentNode = {
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
  return {
    edges: [{
      edgeId: 'edge-task-document',
      edgeType: 'DERIVED_FROM',
      fromNodeId: root.nodeId,
      metadata: {},
      projectId,
      sourceReference: null,
      toNodeId: documentNode.nodeId,
    }],
    nodes: [
      { depth: 0, node: root },
      { depth: 1, node: documentNode },
    ],
    root,
    truncated,
  }
}

function sourceIds() {
  return {
    sourceChunkId: null,
    sourceDocumentId: null,
    sourceIterationId: null,
    sourceProjectId: null,
    sourceRunId: null,
    sourceTaskGraphId: null,
    sourceTaskId: null,
  }
}
