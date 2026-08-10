import { QueryClient } from '@tanstack/react-query'
import { HttpResponse, http } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import {
  createDashboardApiClient,
  DashboardApiError,
  isDashboardApiError,
} from './api'
import {
  createDashboardQueries,
  dashboardQueryKeys,
  priorityDocumentLookupPageLimit,
} from './queries'
import { server } from '../test/mswServer'

const projectId = '11111111-1111-4111-8111-111111111111'
const otherProjectId = '33333333-3333-4333-8333-333333333333'
const artifactId = '22222222-2222-4222-8222-222222222222'

const browserFetch: typeof fetch = (input, init) => {
  const resolvedInput = typeof input === 'string'
    ? new URL(input, window.location.origin).toString()
    : input
  return globalThis.fetch(resolvedInput, init)
}

function createClient() {
  return createDashboardApiClient(browserFetch)
}

afterEach(() => {
  server.resetHandlers()
})

describe('dashboard data access', () => {
  it('serializes every supported same-origin read endpoint exactly', async () => {
    const received: Record<string, { body: unknown; url: URL }> = {}
    server.use(
      http.get('/api/health', ({ request }) => {
        received.health = { body: null, url: new URL(request.url) }
        return HttpResponse.json({ status: 'UP', timestamp: '2026-07-26T00:00:00Z' })
      }),
      http.get('/api/projects', ({ request }) => {
        received.projects = { body: null, url: new URL(request.url) }
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
      http.get(`/api/projects/${projectId}/iterations`, ({ request }) => {
        received.iterations = { body: null, url: new URL(request.url) }
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
      http.get('/api/artifacts', ({ request }) => {
        received.artifacts = { body: null, url: new URL(request.url) }
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
      http.get(`/api/artifacts/TASK/${artifactId}`, ({ request }) => {
        received.artifact = { body: null, url: new URL(request.url) }
        return HttpResponse.json(artifactDetailResponse())
      }),
      http.get('/api/search/keyword', ({ request }) => {
        received.keyword = { body: null, url: new URL(request.url) }
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
      http.post('/api/search/semantic', async ({ request }) => {
        received.semantic = { body: await request.json(), url: new URL(request.url) }
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
      http.post('/api/search/hybrid', async ({ request }) => {
        received.hybrid = { body: await request.json(), url: new URL(request.url) }
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
      http.get('/api/graph/nodes', ({ request }) => {
        received.graphNodes = { body: null, url: new URL(request.url) }
        return HttpResponse.json([])
      }),
      http.get('/api/graph/trace', ({ request }) => {
        received.graphTrace = { body: null, url: new URL(request.url) }
        return HttpResponse.json(graphTraceResponse())
      }),
    )

    const client = createClient()
    await client.health()
    await client.listProjects({ cursor: 'project_cursor', limit: 15 })
    await client.listProjectIterations({ projectId, cursor: 'iteration_cursor', limit: 10 })
    await client.listArtifacts({
      artifactType: 'TASK',
      artifactTypes: ['TASK', 'RUN_RECORD'],
      contentHash: 'a'.repeat(64),
      cursor: 'artifact_cursor',
      iterationId: '  iteration-1  ',
      limit: 25,
      projectId: ` ${projectId} `,
      runId: 'run-1',
      sourceDocumentId: 'document-1',
      sourceIterationId: 'source-iteration-1',
      sourcePath: 'docs/decision.md',
      sourceProjectId: 'source-project-1',
      sourceReferenceCanonicalServerId: projectId,
      sourceReferenceUri: 'p2a://memory/artifacts/1',
      sourceRunId: 'source-run-1',
      sourceTaskGraphId: 'graph-1',
      sourceTaskId: 'source-task-1',
      taskId: artifactId,
    })
    await client.getArtifact({ artifactId, artifactType: 'TASK' })
    await client.keywordSearch({
      artifactType: 'TASK',
      cursor: 'keyword_cursor',
      iterationId: 'iteration-1',
      limit: 20,
      projectId,
      q: '  decision policy  ',
      runId: 'run-1',
      sourcePath: 'docs/decision.md',
      taskId: artifactId,
    })
    await client.semanticSearch({
      artifactType: 'TASK',
      cursor: 'semantic_cursor',
      iterationId: 'iteration-1',
      limit: 20,
      metadataFilters: { owner: 'memory', phase: 'gate-c' },
      projectId,
      q: '  decision policy  ',
      runId: 'run-1',
      sourcePath: 'docs/decision.md',
      taskId: artifactId,
    })
    await client.hybridSearch({
      artifactType: 'TASK',
      candidateLimit: 80,
      cursor: 'hybrid_cursor',
      iterationId: 'iteration-1',
      limit: 20,
      metadataFilters: { owner: 'memory', phase: 'gate-c' },
      projectId,
      q: '  decision policy  ',
      rrfK: 60,
      runId: 'run-1',
      sourcePath: 'docs/decision.md',
      taskId: artifactId,
    })
    await client.listGraphNodes({
      iterationId: 'iteration-1',
      limit: 20,
      nodeKind: 'TASK',
      projectId,
      query: '  approval  ',
    })
    await client.traceGraph({
      direction: 'UPSTREAM',
      iterationId: 'iteration-1',
      maxDepth: 2,
      naturalKey: '  task:source-task-1  ',
      projectId,
    })

    expect(received.health?.url.pathname).toBe('/api/health')
    expect(received.projects?.url.search).toBe('?limit=15&cursor=project_cursor')
    expect(received.iterations?.url.search).toBe('?limit=10&cursor=iteration_cursor')
    expect(received.artifacts?.url.search).toBe(
      `?projectId=${projectId}&iterationId=iteration-1&sourceProjectId=source-project-1&sourceIterationId=source-iteration-1&sourceDocumentId=document-1&sourceTaskGraphId=graph-1&sourceTaskId=source-task-1&sourceRunId=source-run-1&artifactType=TASK&artifactTypes=RUN_RECORD&artifactTypes=TASK&sourcePath=docs%2Fdecision.md&taskId=${artifactId}&runId=run-1&contentHash=${'a'.repeat(64)}&sourceReferenceCanonicalServerId=${projectId}&sourceReferenceUri=p2a%3A%2F%2Fmemory%2Fartifacts%2F1&limit=25&cursor=artifact_cursor`,
    )
    expect(received.artifact?.url.pathname).toBe(`/api/artifacts/TASK/${artifactId}`)
    expect(received.keyword?.url.search).toBe(
      `?q=decision+policy&projectId=${projectId}&iterationId=iteration-1&artifactType=TASK&sourcePath=docs%2Fdecision.md&taskId=${artifactId}&runId=run-1&limit=20&cursor=keyword_cursor`,
    )
    expect(received.semantic?.url.pathname).toBe('/api/search/semantic')
    expect(received.semantic?.body).toEqual({
      artifactType: 'TASK',
      cursor: 'semantic_cursor',
      iterationId: 'iteration-1',
      limit: 20,
      metadataFilters: { owner: 'memory', phase: 'gate-c' },
      projectId,
      q: 'decision policy',
      runId: 'run-1',
      sourcePath: 'docs/decision.md',
      taskId: artifactId,
    })
    expect(received.hybrid?.url.pathname).toBe('/api/search/hybrid')
    expect(received.hybrid?.body).toEqual({
      artifactType: 'TASK',
      candidateLimit: 80,
      cursor: 'hybrid_cursor',
      iterationId: 'iteration-1',
      limit: 20,
      metadataFilters: { owner: 'memory', phase: 'gate-c' },
      projectId,
      q: 'decision policy',
      rrfK: 60,
      runId: 'run-1',
      sourcePath: 'docs/decision.md',
      taskId: artifactId,
    })
    expect(received.graphNodes?.url.search).toBe(`?projectId=${projectId}&iterationId=iteration-1&nodeKind=TASK&query=approval&limit=20`)
    expect(received.graphTrace?.url.search).toBe(`?projectId=${projectId}&naturalKey=task%3Asource-task-1&iterationId=iteration-1&direction=UPSTREAM&maxDepth=2`)
  })

  it('isolates TanStack Query cache entries by scope, search mode, filters, fusion, root, depth, and cursor', async () => {
    let semanticRequests = 0
    let hybridRequests = 0
    let traceRequests = 0
    server.use(
      http.post('/api/search/semantic', () => {
        semanticRequests += 1
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
      http.post('/api/search/hybrid', () => {
        hybridRequests += 1
        return HttpResponse.json({ items: [], nextCursor: null })
      }),
      http.get('/api/graph/trace', () => {
        traceRequests += 1
        return HttpResponse.json(graphTraceResponse())
      }),
    )

    const queries = createDashboardQueries(createClient())
    const queryClient = new QueryClient()
    const semanticFirst = queries.semanticSearch({
      metadataFilters: { owner: 'memory', phase: 'gate-c' },
      projectId,
      q: '  decision  ',
    })
    const semanticEquivalent = queries.semanticSearch({
      metadataFilters: { phase: 'gate-c', owner: 'memory' },
      projectId,
      q: 'decision',
    })
    const semanticOtherScope = queries.semanticSearch({ projectId: otherProjectId, q: 'decision' })
    const hybridFirst = queries.hybridSearch({ candidateLimit: 80, projectId, q: 'decision', rrfK: 60 })
    const hybridOtherFusion = queries.hybridSearch({ candidateLimit: 100, projectId, q: 'decision', rrfK: 60 })
    const traceDepthOne = queries.graphTrace({ maxDepth: 1, naturalKey: 'task:source-task-1', projectId })
    const traceDepthTwo = queries.graphTrace({ maxDepth: 2, naturalKey: 'task:source-task-1', projectId })

    await queryClient.fetchQuery({ ...semanticFirst, staleTime: Infinity })
    await queryClient.fetchQuery({ ...semanticEquivalent, staleTime: Infinity })
    await queryClient.fetchQuery({ ...semanticOtherScope, staleTime: Infinity })
    await queryClient.fetchQuery({ ...hybridFirst, staleTime: Infinity })
    await queryClient.fetchQuery({ ...hybridOtherFusion, staleTime: Infinity })
    await queryClient.fetchQuery({ ...traceDepthOne, staleTime: Infinity })
    await queryClient.fetchQuery({ ...traceDepthTwo, staleTime: Infinity })

    expect(semanticRequests).toBe(2)
    expect(hybridRequests).toBe(2)
    expect(traceRequests).toBe(2)
    expect(semanticFirst.queryKey).toEqual(semanticEquivalent.queryKey)
    expect(semanticFirst.queryKey).not.toEqual(semanticOtherScope.queryKey)
    expect(hybridFirst.queryKey).not.toEqual(hybridOtherFusion.queryKey)
    expect(traceDepthOne.queryKey).not.toEqual(traceDepthTwo.queryKey)
    expect(dashboardQueryKeys.projectIterations({ projectId })).not.toEqual(
      dashboardQueryKeys.projectIterations({ projectId: otherProjectId }),
    )
    expect(dashboardQueryKeys.keywordSearch({ cursor: 'page-one', projectId, q: 'decision' })).not.toEqual(
      dashboardQueryKeys.keywordSearch({ cursor: 'page-two', projectId, q: 'decision' }),
    )

    queryClient.clear()
  })

  it('loads only the first bounded page for each exact current-iteration priority source path', async () => {
    const priorityRequests: URL[] = []
    const priorityScope = {
      iterationId: '44444444-4444-4444-8444-444444444444',
      projectId,
      sourceIterationId: 'v4-dashboard-refresh',
    }
    server.use(
      http.get('/api/artifacts', ({ request }) => {
        priorityRequests.push(new URL(request.url))
        return HttpResponse.json({ items: [], nextCursor: 'next-page-must-not-be-requested' })
      }),
    )

    const queryClient = new QueryClient()
    const query = createDashboardQueries(createClient()).priorityDocumentLookup(priorityScope)
    await queryClient.fetchQuery({ ...query, staleTime: Infinity })

    expect(priorityRequests).toHaveLength(5)
    expect(priorityRequests.map((url) => url.search)).toEqual([
      `?projectId=${projectId}&iterationId=${priorityScope.iterationId}&artifactType=DOCUMENT_SNAPSHOT&sourcePath=iterations%2Fv4-dashboard-refresh%2Fgate-b-spec%2Fproduct-spec.md&limit=${priorityDocumentLookupPageLimit}`,
      `?projectId=${projectId}&iterationId=${priorityScope.iterationId}&artifactType=DOCUMENT_SNAPSHOT&sourcePath=iterations%2Fv4-dashboard-refresh%2Fgate-b-spec%2Fimplementation-plan.md&limit=${priorityDocumentLookupPageLimit}`,
      `?projectId=${projectId}&iterationId=${priorityScope.iterationId}&artifactType=TASK_GRAPH&sourcePath=iterations%2Fv4-dashboard-refresh%2Fgate-c-task-graph%2Ftask-graph.json&limit=${priorityDocumentLookupPageLimit}`,
      `?projectId=${projectId}&iterationId=${priorityScope.iterationId}&artifactType=DOCUMENT_SNAPSHOT&sourcePath=iterations%2Fv4-dashboard-refresh%2Fgate-b-spec%2Fexperience-spec.json&limit=${priorityDocumentLookupPageLimit}`,
      `?projectId=${projectId}&iterationId=${priorityScope.iterationId}&artifactType=DOCUMENT_SNAPSHOT&sourcePath=iterations%2Fv4-dashboard-refresh%2Fgate-d-review%2Freview.json&limit=${priorityDocumentLookupPageLimit}`,
    ])

    queryClient.clear()
  })

  it('propagates AbortSignal cancellation to the browser fetch request', async () => {
    let notifyRequestStarted: (() => void) | undefined
    let notifyAbortObserved: (() => void) | undefined
    const requestStarted = new Promise<void>((resolve) => {
      notifyRequestStarted = resolve
    })
    const abortObserved = new Promise<void>((resolve) => {
      notifyAbortObserved = resolve
    })
    server.use(
      http.get('/api/health', async ({ request }) => {
        notifyRequestStarted?.()
        await new Promise<void>((resolve) => {
          request.signal.addEventListener('abort', () => {
            notifyAbortObserved?.()
            resolve()
          }, { once: true })
        })
        return HttpResponse.json({ status: 'UP', timestamp: '2026-07-26T00:00:00Z' })
      }),
    )

    const controller = new AbortController()
    const health = createClient().health({ signal: controller.signal })
    await requestStarted
    controller.abort()

    await expect(health).rejects.toMatchObject({ name: 'AbortError' })
    await abortObserved
  })

  it('converts unknown server failures into a public error without reflecting response details', async () => {
    const sensitiveDetail = 'token=do-not-expose /private/operator/notes'
    server.use(
      http.get('/api/health', () => HttpResponse.json(
        { error: 'unrecognized_internal_error', message: sensitiveDetail },
        { status: 503 },
      )),
    )

    let caught: unknown
    try {
      await createClient().health()
    } catch (error) {
      caught = error
    }

    expect(isDashboardApiError(caught)).toBe(true)
    expect(caught).toBeInstanceOf(DashboardApiError)
    if (isDashboardApiError(caught)) {
      expect(caught.code).toBe('service_unavailable')
      expect(caught.message).toBe('The dashboard service is unavailable.')
      expect(caught.message).not.toContain(sensitiveDetail)
      expect(caught.status).toBe(503)
    }
  })

  it.each([
    {
      bodyCode: 'embedding_provider_not_configured',
      expectedCode: 'embedding_provider_not_configured',
      expectedMessage: 'Semantic search is unavailable because its embedding provider is not configured.',
      status: 503,
    },
    {
      bodyCode: 'embedding_provider_unavailable',
      expectedCode: 'embedding_provider_unavailable',
      expectedMessage: 'Semantic search is temporarily unavailable because its embedding provider is unavailable.',
      status: 503,
    },
    {
      bodyCode: 'bff_response_too_large',
      expectedCode: 'bff_response_too_large',
      expectedMessage: 'The dashboard backend response is too large.',
      status: 502,
    },
  ])('preserves the public $bodyCode response code', async ({ bodyCode, expectedCode, expectedMessage, status }) => {
    server.use(
      http.post('/api/search/semantic', () => HttpResponse.json(
        { error: bodyCode, message: 'token=do-not-expose' },
        { status },
      )),
    )

    let caught: unknown
    try {
      await createClient().semanticSearch({ q: 'decision' })
    } catch (error) {
      caught = error
    }

    expect(isDashboardApiError(caught)).toBe(true)
    if (isDashboardApiError(caught)) {
      expect(caught.code).toBe(expectedCode)
      expect(caught.message).toBe(expectedMessage)
      expect(caught.message).not.toContain('token=do-not-expose')
      expect(caught.status).toBe(status)
    }
  })
})

function artifactDetailResponse() {
  return {
    artifactId,
    artifactType: 'TASK',
    createdAt: '2026-07-26T00:00:00Z',
    iterationId: null,
    lineage: {
      artifactRefs: [],
      contentHash: null,
      documentId: null,
      iterationId: null,
      projectId,
      runId: null,
      snapshotVersion: null,
      taskGraphId: null,
      taskId: artifactId,
    },
    mediaType: 'application/json',
    metadata: {},
    projectId,
    rawContent: '{}',
    source: {},
    title: 'Task',
    updatedAt: null,
  }
}

function graphTraceResponse() {
  return {
    edges: [],
    nodes: [],
    root: {
      content: null,
      documentId: null,
      iterationId: null,
      label: 'Task',
      metadata: {},
      naturalKey: 'task:source-task-1',
      nodeId: artifactId,
      nodeKind: 'TASK',
      projectId,
      runId: null,
      taskId: artifactId,
    },
    truncated: false,
  }
}
