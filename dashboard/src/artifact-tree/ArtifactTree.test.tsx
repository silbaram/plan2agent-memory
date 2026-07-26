import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import type { ComponentProps } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import { createDashboardApiClient } from '../data-access'
import type { DashboardArtifactType } from '../data-access'
import { server } from '../test/mswServer'
import { ArtifactTree } from './ArtifactTree'
import type { ArtifactTreeSelection } from './ArtifactTree'

const projectId = '11111111-1111-4111-8111-111111111111'
const iterationId = '22222222-2222-4222-8222-222222222222'

const browserFetch: typeof fetch = (input, init) => {
  const resolvedInput = typeof input === 'string'
    ? new URL(input, window.location.origin).toString()
    : input
  return globalThis.fetch(resolvedInput, init)
}

afterEach(() => {
  cleanup()
  server.resetHandlers()
})

describe('ArtifactTree', () => {
  it('loads descendants only when their branch expands and excludes document chunks', async () => {
    const iterationRequests: URL[] = []
    const artifactRequests: URL[] = []
    server.use(
      http.get('/api/projects', () => HttpResponse.json(page([project('Atlas')]))),
      http.get(`/api/projects/${projectId}/iterations`, ({ request }) => {
        iterationRequests.push(new URL(request.url))
        return HttpResponse.json(page([iteration('Iteration one')]))
      }),
      http.get('/api/artifacts', ({ request }) => {
        artifactRequests.push(new URL(request.url))
        return HttpResponse.json(page([
          artifact('DOCUMENT_CHUNK', 'hidden-chunk', '숨겨진 청크'),
          artifact('TASK', 'ready-task', '준비된 작업'),
        ]))
      }),
    )

    renderArtifactTree()

    const projectItem = await screen.findByRole('treeitem', { name: '프로젝트 Atlas' })
    expect(iterationRequests).toHaveLength(0)
    expect(artifactRequests).toHaveLength(0)

    fireEvent.click(projectItem)
    const iterationItem = await screen.findByRole('treeitem', { name: '이터레이션 Iteration one' })
    expect(iterationRequests).toHaveLength(1)
    expect(artifactRequests).toHaveLength(0)

    fireEvent.click(iterationItem)
    expect(await screen.findByRole('treeitem', { name: '작업 준비된 작업' })).toBeTruthy()
    expect(screen.queryByText('숨겨진 청크')).toBeNull()

    const requestedArtifactTypes = artifactRequests[0]?.searchParams.getAll('artifactTypes')
    expect(requestedArtifactTypes).toEqual([
      'DOCUMENT_SNAPSHOT',
      'PROPOSAL',
      'RUN_RECORD',
      'TASK',
      'TASK_GRAPH',
    ])
    expect(requestedArtifactTypes).not.toContain('DOCUMENT_CHUNK')
    expect(artifactRequests[0]?.searchParams.get('projectId')).toBe(projectId)
    expect(artifactRequests[0]?.searchParams.get('iterationId')).toBe(iterationId)
  })

  it('appends every keyset page without duplicates at each tree level', async () => {
    const projectCursors: Array<string | null> = []
    const iterationCursors: Array<string | null> = []
    const artifactCursors: Array<string | null> = []
    server.use(
      http.get('/api/projects', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        projectCursors.push(cursor)
        return cursor === null
          ? HttpResponse.json(page([project('Atlas')], 'project-page-2'))
          : HttpResponse.json(page([project('Atlas'), project('Beacon', '33333333-3333-4333-8333-333333333333')]))
      }),
      http.get(`/api/projects/${projectId}/iterations`, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        iterationCursors.push(cursor)
        return cursor === null
          ? HttpResponse.json(page([iteration('Iteration one')], 'iteration-page-2'))
          : HttpResponse.json(page([iteration('Iteration one'), iteration('Iteration two', '44444444-4444-4444-8444-444444444444')]))
      }),
      http.get('/api/artifacts', ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        artifactCursors.push(cursor)
        return cursor === null
          ? HttpResponse.json(page([artifact('TASK', 'first-task', '첫 번째 작업')], 'artifact-page-2'))
          : HttpResponse.json(page([
              artifact('TASK', 'first-task', '첫 번째 작업'),
              artifact('PROPOSAL', 'second-proposal', '두 번째 제안'),
            ]))
      }),
    )

    renderArtifactTree()

    await screen.findByRole('treeitem', { name: '프로젝트 Atlas' })
    fireEvent.click(screen.getByRole('button', { name: '더 불러오기' }))
    expect(await screen.findByRole('treeitem', { name: '프로젝트 Beacon' })).toBeTruthy()
    expect(screen.getAllByRole('treeitem', { name: '프로젝트 Atlas' })).toHaveLength(1)
    expect(projectCursors).toEqual([null, 'project-page-2'])

    fireEvent.click(screen.getByRole('treeitem', { name: '프로젝트 Atlas' }))
    await screen.findByRole('treeitem', { name: '이터레이션 Iteration one' })
    fireEvent.click(screen.getByRole('button', { name: '더 불러오기' }))
    expect(await screen.findByRole('treeitem', { name: '이터레이션 Iteration two' })).toBeTruthy()
    expect(screen.getAllByRole('treeitem', { name: '이터레이션 Iteration one' })).toHaveLength(1)
    expect(iterationCursors).toEqual([null, 'iteration-page-2'])

    fireEvent.click(screen.getByRole('treeitem', { name: '이터레이션 Iteration one' }))
    await screen.findByRole('treeitem', { name: '작업 첫 번째 작업' })
    fireEvent.click(screen.getByRole('button', { name: '더 불러오기' }))
    expect(await screen.findByRole('treeitem', { name: '제안 두 번째 제안' })).toBeTruthy()
    expect(screen.getAllByRole('treeitem', { name: '작업 첫 번째 작업' })).toHaveLength(1)
    expect(artifactCursors).toEqual([null, 'artifact-page-2'])
  })

  it('keeps an iteration failure inside its project branch and retries that branch', async () => {
    let iterationAttempts = 0
    server.use(
      http.get('/api/projects', () => HttpResponse.json(page([project('Atlas')]))),
      http.get(`/api/projects/${projectId}/iterations`, () => {
        iterationAttempts += 1
        return iterationAttempts === 1
          ? new HttpResponse(null, { status: 503 })
          : HttpResponse.json(page([iteration('Recovered iteration')]))
      }),
    )

    renderArtifactTree()

    fireEvent.click(await screen.findByRole('treeitem', { name: '프로젝트 Atlas' }))
    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('이터레이션을 불러올 수 없습니다')).toBeTruthy()
    expect(screen.getByRole('treeitem', { name: '프로젝트 Atlas' })).toBeTruthy()

    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('treeitem', { name: '이터레이션 Recovered iteration' })).toBeTruthy()
    expect(iterationAttempts).toBe(2)
  })

  it('supports keyboard expand, collapse, focus movement, and an accessible current selection', async () => {
    const selections: ArtifactTreeSelection[] = []
    server.use(
      http.get('/api/projects', () => HttpResponse.json(page([project('Atlas')]))),
      http.get(`/api/projects/${projectId}/iterations`, () => HttpResponse.json(page([iteration('Iteration one')]))),
      http.get('/api/artifacts', () => HttpResponse.json(page([artifact('TASK', 'approval-task', 'Gate D 승인')]))),
    )

    renderArtifactTree({
      onArtifactSelect: (selection) => {
        selections.push(selection)
      },
    })

    const projectItem = await screen.findByRole('treeitem', { name: '프로젝트 Atlas' })
    projectItem.focus()
    fireEvent.keyDown(projectItem, { key: 'ArrowRight' })
    expect(projectItem.getAttribute('aria-expanded')).toBe('true')

    const iterationItem = await screen.findByRole('treeitem', { name: '이터레이션 Iteration one' })
    fireEvent.keyDown(projectItem, { key: 'ArrowRight' })
    expect(document.activeElement).toBe(iterationItem)

    fireEvent.keyDown(iterationItem, { key: 'ArrowRight' })
    expect(iterationItem.getAttribute('aria-expanded')).toBe('true')

    const artifactItem = await screen.findByRole('treeitem', { name: '작업 Gate D 승인' })
    fireEvent.keyDown(iterationItem, { key: 'ArrowRight' })
    expect(document.activeElement).toBe(artifactItem)

    fireEvent.keyDown(artifactItem, { key: 'Enter' })
    expect(screen.getByRole('treeitem', { current: true, name: '작업 Gate D 승인' })).toBe(artifactItem)
    expect(selections).toEqual([{
      artifactId: 'approval-task',
      artifactType: 'TASK',
      iterationId,
      projectId,
    }])

    fireEvent.keyDown(artifactItem, { key: 'ArrowLeft' })
    expect(document.activeElement).toBe(iterationItem)
    fireEvent.keyDown(iterationItem, { key: 'ArrowLeft' })
    expect(iterationItem.getAttribute('aria-expanded')).toBe('false')
  })
})

function renderArtifactTree(props: Partial<ComponentProps<typeof ArtifactTree>> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        gcTime: 0,
        retry: false,
      },
    },
  })
  const apiClient = createDashboardApiClient(browserFetch)

  return render(
    <QueryClientProvider client={queryClient}>
      <ArtifactTree apiClient={apiClient} {...props} />
    </QueryClientProvider>,
  )
}

function page<T>(items: readonly T[], nextCursor: string | null = null) {
  return { items, nextCursor }
}

function project(name: string, id: string = projectId) {
  return {
    canonicalServerId: 'server-1',
    createdAt: '2026-07-26T00:00:00Z',
    metadata: {},
    name,
    projectId: id,
    rootPath: `/projects/${name.toLowerCase()}`,
    sourceProjectId: `source-${id}`,
    sourceReference: null,
    updatedAt: null,
  }
}

function iteration(label: string, id: string = iterationId) {
  return {
    createdAt: '2026-07-26T00:00:00Z',
    iterationId: id,
    label,
    metadata: {},
    projectId,
    sourceIterationId: `source-${id}`,
    sourceReference: null,
    status: 'active',
    updatedAt: null,
  }
}

function artifact(artifactType: DashboardArtifactType | 'DOCUMENT_CHUNK', artifactId: string, title: string) {
  return {
    artifactId,
    artifactType,
    contentHash: null,
    createdAt: null,
    iterationId,
    lineage: {
      contentHash: null,
      iterationId,
      projectId,
      runId: null,
      snapshotVersion: null,
      sourcePath: null,
      taskId: null,
    },
    metadata: {},
    projectId,
    runId: null,
    snapshotVersion: null,
    sourceIds: {
      sourceChunkId: null,
      sourceDocumentId: null,
      sourceIterationId: null,
      sourceProjectId: null,
      sourceRunId: null,
      sourceTaskGraphId: null,
      sourceTaskId: null,
    },
    sourcePath: null,
    sourceReference: null,
    taskId: null,
    title,
    updatedAt: null,
  }
}
