import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { BrowserRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  ArtifactDetail,
  ArtifactDetailRequest,
  ArtifactLookupItem,
  ArtifactListRequest,
  DashboardApiClient,
  Page,
} from './data-access'
import { BrowseRouteSlot } from './dashboardShell'

const projectId = 'project-1'
const iterationId = 'iteration-1'
const sourceIterationId = 'source-iteration-1'

function setLocation(search: string) {
  window.history.replaceState({}, '', `/browse${search}`)
}

function deferred<Value>() {
  let resolvePromise: (value: Value) => void = () => {
    throw new Error('Deferred promise was resolved before its initializer ran.')
  }
  const promise = new Promise<Value>((resolve) => {
    resolvePromise = resolve
  })

  return { promise, resolve: resolvePromise }
}

function artifactLookup(overrides: Partial<ArtifactLookupItem> = {}): ArtifactLookupItem {
  const artifactId = overrides.artifactId ?? 'product-spec-1'
  const artifactType = overrides.artifactType ?? 'DOCUMENT_SNAPSHOT'
  return {
    artifactId,
    artifactType,
    contentHash: null,
    createdAt: '2026-08-11T00:00:00Z',
    iterationId,
    lineage: {
      contentHash: null,
      iterationId,
      projectId,
      runId: null,
      snapshotVersion: 1,
      sourcePath: overrides.sourcePath ?? `iterations/${sourceIterationId}/gate-b-spec/product-spec.md`,
      taskId: null,
    },
    metadata: {},
    projectId,
    runId: null,
    snapshotVersion: 1,
    sourceIds: {
      sourceChunkId: null,
      sourceDocumentId: null,
      sourceIterationId,
      sourceProjectId: 'source-project-1',
      sourceRunId: null,
      sourceTaskGraphId: null,
      sourceTaskId: null,
    },
    sourcePath: `iterations/${sourceIterationId}/gate-b-spec/product-spec.md`,
    sourceReference: null,
    taskId: null,
    title: 'Product specification',
    updatedAt: null,
    ...overrides,
  }
}

function artifactDetail(request: ArtifactDetailRequest, overrides: Partial<ArtifactDetail> = {}): ArtifactDetail {
  return {
    artifactId: request.artifactId,
    artifactType: request.artifactType,
    createdAt: '2026-08-11T00:00:00Z',
    iterationId,
    lineage: {
      artifactRefs: [
        { artifactId: 'task-1', artifactType: 'TASK', sourcePath: 'tasks/task-1.json' },
        { artifactId: 'run-1', artifactType: 'RUN_RECORD', sourcePath: 'runs/run-1.json' },
      ],
      contentHash: null,
      documentId: 'document-1',
      iterationId,
      projectId,
      runId: null,
      snapshotVersion: 1,
      taskGraphId: null,
      taskId: null,
    },
    mediaType: 'text/markdown',
    metadata: {
      approval_audit: JSON.stringify({ approved_at: '2026-08-11T00:00:00Z', approved_by: 'owner' }),
      status: 'approved',
    },
    projectId,
    rawContent: '# 제품 명세\n\n승인된 핵심 문서입니다.',
    source: {
      sourceDocumentId: 'document-1',
      sourceIterationId,
      sourcePath: `iterations/${sourceIterationId}/gate-b-spec/product-spec.md`,
      sourceProjectId: 'source-project-1',
      sourceReference: null,
      sourceRunId: null,
      sourceTaskGraphId: null,
      sourceTaskId: null,
    },
    title: '제품 명세 원본',
    updatedAt: null,
    ...overrides,
  }
}

function page<Value>(items: readonly Value[]): Page<Value> {
  return { items, nextCursor: null }
}

function createClient({
  getArtifact = async (request) => artifactDetail(request),
  listArtifacts = async () => page([]),
}: {
  readonly getArtifact?: (request: ArtifactDetailRequest, options?: { readonly signal?: AbortSignal }) => Promise<ArtifactDetail>
  readonly listArtifacts?: (request?: ArtifactListRequest, options?: { readonly signal?: AbortSignal }) => Promise<Page<ArtifactLookupItem>>
} = {}): DashboardApiClient {
  return {
    getArtifact,
    health: async () => ({ status: 'UP', timestamp: '2026-08-11T00:00:00Z' }),
    hybridSearch: async () => page([]),
    keywordSearch: async () => page([]),
    listArtifacts,
    listGraphNodes: async () => [],
    listProjectIterations: async () => page([]),
    listProjects: async () => page([]),
    semanticSearch: async () => page([]),
    traceGraph: async () => ({
      edges: [],
      nodes: [],
      root: {
        content: null,
        documentId: null,
        iterationId: null,
        label: 'Root',
        metadata: {},
        naturalKey: 'root',
        nodeId: 'root',
        nodeKind: 'DOCUMENT',
        projectId,
        runId: null,
        taskId: null,
      },
      truncated: false,
    }),
  }
}

function renderBrowse(apiClient: DashboardApiClient) {
  return render(
    <BrowserRouter>
      <BrowseRouteSlot apiClient={apiClient} />
    </BrowserRouter>,
  )
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

beforeEach(() => {
  setLocation('')
})

describe('BrowseRouteSlot', () => {
  it('keeps the cursor and scope while grouping exact priority documents before supporting documents', async () => {
    const product = artifactLookup()
    const supporting = artifactLookup({
      artifactId: 'supporting-document-1',
      sourcePath: `iterations/${sourceIterationId}/notes/supporting.md`,
      title: 'Supporting document',
    })
    const linkedTask = artifactLookup({
      artifactId: 'task-1',
      artifactType: 'TASK',
      sourcePath: 'tasks/task-1.json',
      taskId: 'task-1',
      title: '준비 작업',
    })
    const completedRun = artifactLookup({
      artifactId: 'run-1',
      artifactType: 'RUN_RECORD',
      metadata: { completedAt: '2026-08-11T01:00:00Z', result: 'passed', status: 'finished' },
      runId: 'run-1',
      sourcePath: 'runs/run-1.json',
      title: '완료 실행',
    })
    const requests: ArtifactListRequest[] = []
    const listArtifacts = vi.fn(async (request?: ArtifactListRequest) => {
      const currentRequest = request ?? {}
      requests.push(currentRequest)
      return currentRequest.sourcePath === undefined
        ? page([product, supporting, linkedTask, completedRun])
        : currentRequest.sourcePath.endsWith('product-spec.md')
          ? page([product])
          : page([])
    })
    setLocation(`?projectId=${projectId}&iterationId=${iterationId}&sourceIterationId=${sourceIterationId}&cursor=second-page`)

    renderBrowse(createClient({ listArtifacts }))

    const productButton = await screen.findByRole('button', { name: /Product specification/ })
    expect(screen.getByRole('heading', { name: '핵심 P2A 문서' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: '관련 산출물' })).toBeTruthy()
    expect(screen.getAllByRole('button', { name: /Product specification/ })).toHaveLength(1)
    expect(requests.filter((request) => request.sourcePath !== undefined)).toHaveLength(5)
    expect(requests.find((request) => request.sourcePath === undefined)).toMatchObject({
      cursor: 'second-page',
      iterationId,
      limit: 50,
      projectId,
    })

    const initialContext = screen.getByRole('complementary', { name: '실행 맥락' })
    expect(within(initialContext).getByText('Gate B').parentElement?.getAttribute('data-status')).toBe('pending')

    fireEvent.click(productButton)

    expect(await screen.findByRole('heading', { name: '제품 명세 원본' })).toBeTruthy()
    expect(window.location.search).toContain('cursor=second-page')
    expect(window.location.search).toContain(`sourceIterationId=${sourceIterationId}`)
    expect(window.location.search).toContain('selectedArtifactId=product-spec-1')
    expect(window.location.search).toContain('selectedArtifactType=DOCUMENT_SNAPSHOT')
    expect(screen.getByRole('button', { current: 'page', name: /Product specification/ })).toBeTruthy()

    const context = screen.getByRole('complementary', { name: '실행 맥락' })
    expect(within(context).getByText('준비 작업')).toBeTruthy()
    expect(within(context).getAllByText('완료 실행')).toHaveLength(2)
    expect(within(context).getByText('approved')).toBeTruthy()
  })

  it('distinguishes unselected, loading, empty, and error states without widening the browse scope', async () => {
    const deferredPages = deferred<Page<ArtifactLookupItem>>()
    const loadingClient = createClient({ listArtifacts: async () => deferredPages.promise })

    renderBrowse(loadingClient)
    expect(screen.getByText('핵심 문서 범위를 선택하세요')).toBeTruthy()
    cleanup()

    setLocation(`?projectId=${projectId}&iterationId=${iterationId}&sourceIterationId=${sourceIterationId}`)
    renderBrowse(loadingClient)
    expect(screen.getByRole('status', { name: '핵심 문서를 불러오는 중' })).toBeTruthy()
    cleanup()

    const emptyClient = createClient({ listArtifacts: async () => page([]) })
    renderBrowse(emptyClient)
    expect(await screen.findByText('표시할 문서가 없습니다')).toBeTruthy()
    cleanup()

    const failedClient = createClient({ listArtifacts: async () => {
      throw new Error('private upstream detail')
    } })
    renderBrowse(failedClient)
    expect((await screen.findByRole('alert')).textContent).toContain('핵심 문서를 표시할 수 없습니다')
  })

  it('removes a stale source iteration URL parameter when the newly selected artifact has no canonical source iteration', async () => {
    const sourceLessSupportingDocument = artifactLookup({
      artifactId: 'source-less-document',
      sourceIds: {
        sourceChunkId: null,
        sourceDocumentId: null,
        sourceIterationId: null,
        sourceProjectId: 'source-project-1',
        sourceRunId: null,
        sourceTaskGraphId: null,
        sourceTaskId: null,
      },
      sourcePath: 'documents/source-less.md',
      title: 'Source-less supporting document',
    })
    const listArtifacts = async (request?: ArtifactListRequest) => request?.sourcePath === undefined
      ? page([sourceLessSupportingDocument])
      : page([])
    setLocation(`?projectId=${projectId}&iterationId=${iterationId}&sourceIterationId=${sourceIterationId}`)

    renderBrowse(createClient({ listArtifacts }))

    fireEvent.click(await screen.findByRole('button', { name: /Source-less supporting document/ }))
    await waitFor(() => {
      expect(new URLSearchParams(window.location.search).has('sourceIterationId')).toBe(false)
    })
  })

  it('keeps a newer selected document when the previous detail request resolves after its abort signal', async () => {
    const product = artifactLookup()
    const supporting = artifactLookup({
      artifactId: 'supporting-document-1',
      sourcePath: `iterations/${sourceIterationId}/notes/supporting.md`,
      title: 'Supporting document',
    })
    const firstDetail = deferred<ArtifactDetail>()
    const secondDetail = deferred<ArtifactDetail>()
    const firstRequestAborts: boolean[] = []
    const getArtifact = vi.fn((request: ArtifactDetailRequest, options?: { readonly signal?: AbortSignal }) => {
      if (request.artifactId === product.artifactId) {
        options?.signal?.addEventListener('abort', () => {
          firstRequestAborts.push(true)
        }, { once: true })
        return firstDetail.promise
      }
      return secondDetail.promise
    })
    const listArtifacts = async (request?: ArtifactListRequest) => request?.sourcePath === undefined
      ? page([product, supporting])
      : request.sourcePath.endsWith('product-spec.md')
        ? page([product])
        : page([])
    setLocation(`?projectId=${projectId}&iterationId=${iterationId}&sourceIterationId=${sourceIterationId}&selectedArtifactType=DOCUMENT_SNAPSHOT&selectedArtifactId=${product.artifactId}`)

    renderBrowse(createClient({ getArtifact, listArtifacts }))

    await screen.findByRole('button', { name: /Supporting document/ })
    fireEvent.click(screen.getByRole('button', { name: /Supporting document/ }))
    await waitFor(() => {
      expect(firstRequestAborts).toEqual([true])
    })

    secondDetail.resolve(artifactDetail(
      { artifactId: supporting.artifactId, artifactType: 'DOCUMENT_SNAPSHOT' },
      { title: '새 문서' },
    ))
    expect(await screen.findByRole('heading', { name: '새 문서' })).toBeTruthy()

    firstDetail.resolve(artifactDetail(
      { artifactId: product.artifactId, artifactType: 'DOCUMENT_SNAPSHOT' },
      { title: '이전 문서' },
    ))
    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: '이전 문서' })).toBeNull()
    })
  })
})
