import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import type { ArtifactDetail, ArtifactLookupItem, DashboardApiClient, DashboardArtifactType } from './data-access'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { App } from './App'
import { ArtifactRouteSlot, BrowseRouteSlot } from './dashboardShell'

function setLocation(pathname: string) {
  window.history.replaceState({}, '', pathname)
}

describe('App', () => {
  beforeEach(() => {
    setLocation('/browse')
  })

  afterEach(() => {
    cleanup()
    setLocation('/')
  })

  it('renders the accessible read-only browse shell', () => {
    render(<App />)

    expect(screen.getByRole('navigation', { name: '대시보드 탐색' })).toBeTruthy()
    expect(screen.getByRole('navigation', { name: '주요 탐색' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: '산출물 탐색' })).toBeTruthy()
    expect(screen.getByRole('status', { name: '프로젝트 목록을 불러오는 중' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '본문으로 건너뛰기' }).getAttribute('href')).toBe('#main-content')
  })

  it('keeps the approved wide-workbench regions and Gate progress in the shell', () => {
    render(<App />)

    const gateProgress = screen.getByRole('navigation', { name: 'Gate 진행 상태' })
    expect(gateProgress).toBeTruthy()
    expect(within(gateProgress).getByText('Task graph').closest('li')?.getAttribute('aria-current')).toBe('step')
    expect(screen.getByTestId('dashboard-workbench')).toBeTruthy()
    expect(screen.getByTestId('workbench-library')).toBeTruthy()
    expect(screen.getByTestId('workbench-content')).toBeTruthy()
    expect(screen.getByTestId('workbench-context')).toBeTruthy()
    expect(screen.getByRole('complementary', { name: '문서 탐색' })).toBeTruthy()
    expect(screen.getByRole('complementary', { name: '실행 맥락' })).toBeTruthy()
  })

  it('uses a progressive context disclosure below the wide workbench without changing route state', () => {
    const defaultMatchMedia = window.matchMedia
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        addEventListener: () => {},
        addListener: () => {},
        dispatchEvent: () => false,
        matches: query === '(max-width: 70rem)',
        media: query,
        onchange: null,
        removeEventListener: () => {},
        removeListener: () => {},
      }),
      writable: true,
    })
    setLocation('/browse?projectId=project-1&iterationId=iteration-1&cursor=page-2')

    try {
      render(<App />)

      const workbench = screen.getByTestId('dashboard-workbench')
      expect(Array.from(workbench.children).map((child) => child.getAttribute('data-testid'))).toEqual([
        'workbench-library',
        'workbench-content',
        'workbench-context',
      ])

      const context = screen.getByTestId('workbench-context')
      const toggle = screen.getByRole('button', { name: '맥락 펼치기' })
      expect(context.getAttribute('data-context-layout')).toBe('disclosure')
      expect(toggle.getAttribute('aria-expanded')).toBe('false')
      expect(document.getElementById('workbench-context-content')?.hidden).toBe(true)

      fireEvent.click(toggle)

      expect(screen.getByRole('button', { name: '맥락 접기' }).getAttribute('aria-expanded')).toBe('true')
      expect(document.getElementById('workbench-context-content')?.hidden).toBe(false)
      expect(window.location.search).toBe('?projectId=project-1&iterationId=iteration-1&cursor=page-2')
    } finally {
      Object.defineProperty(window, 'matchMedia', {
        configurable: true,
        value: defaultMatchMedia,
        writable: true,
      })
    }
  })

  it('keeps route navigation keyboard focusable and opens the search slot', () => {
    render(<App />)

    const mainNavigation = screen.getAllByRole('navigation', { name: '주요 탐색' })[0]
    const searchLink = within(mainNavigation).getByRole('link', { name: '검색' })
    searchLink.focus()

    expect(document.activeElement).toBe(searchLink)

    fireEvent.click(searchLink)

    expect(screen.getByRole('heading', { name: '검색' })).toBeTruthy()
    expect(screen.getByRole('status').textContent).toContain('기본 방식은 키워드 검색입니다')
  })

  it('provides artifact, trace, and error state route slots', () => {
    setLocation('/artifact/TASK/example-artifact')
    const artifactRoute = render(<App />)

    expect(screen.getByRole('heading', { name: '산출물 상세' })).toBeTruthy()
    expect(screen.getByRole('status').textContent).toContain('산출물을 불러오는 중')

    artifactRoute.unmount()
    setLocation('/trace/DOCUMENT/example-artifact')
    const traceRoute = render(<App />)

    expect(screen.getByRole('heading', { name: '계보 추적' })).toBeTruthy()
    expect(screen.getByRole('status', { name: '추적 노드 목록을 불러오는 중' })).toBeTruthy()

    traceRoute.unmount()
    setLocation('/not-a-dashboard-route')
    render(<App />)

    expect(screen.getByRole('heading', { name: '화면을 찾을 수 없습니다' })).toBeTruthy()
    expect(screen.getByRole('alert').textContent).toContain('잘못된 대시보드 경로')
  })

  it('uses browse selection parameters for the existing controlled tree and renders a selected artifact detail', async () => {
    const selections: Array<{ readonly artifactId: string; readonly artifactType: string }> = []
    const apiClient: DashboardApiClient = {
      getArtifact: async ({ artifactId, artifactType }) => {
        selections.push({ artifactId, artifactType })
        return artifactDetail(artifactId, artifactType)
      },
      health: async () => ({ status: 'UP', timestamp: '2026-07-26T00:00:00Z' }),
      hybridSearch: async () => ({ items: [], nextCursor: null }),
      keywordSearch: async () => ({ items: [], nextCursor: null }),
      listArtifacts: async () => ({
        items: [artifactLookup()],
        nextCursor: null,
      }),
      listGraphNodes: async () => [],
      listProjectIterations: async () => ({
        items: [{
          createdAt: '2026-07-26T00:00:00Z',
          iterationId: 'iteration-1',
          label: 'Iteration one',
          metadata: {},
          projectId: 'project-1',
          sourceIterationId: 'source-iteration-1',
          sourceReference: null,
          status: 'active',
          updatedAt: null,
        }],
        nextCursor: null,
      }),
      listProjects: async () => ({
        items: [{
          canonicalServerId: 'server-1',
          createdAt: '2026-07-26T00:00:00Z',
          metadata: {},
          name: 'Atlas',
          projectId: 'project-1',
          rootPath: '/projects/atlas',
          sourceProjectId: 'source-project-1',
          sourceReference: null,
          updatedAt: null,
        }],
        nextCursor: null,
      }),
      semanticSearch: async () => ({ items: [], nextCursor: null }),
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
          projectId: 'project-1',
          runId: null,
          taskId: null,
        },
        truncated: false,
      }),
    }

    setLocation('/browse')
    const browse = render(
      <BrowserRouter>
        <BrowseRouteSlot apiClient={apiClient} />
      </BrowserRouter>,
    )

    fireEvent.click(await screen.findByRole('treeitem', { name: '프로젝트 Atlas' }))
    fireEvent.click(await screen.findByRole('treeitem', { name: '이터레이션 Iteration one' }))
    const artifact = await screen.findByRole('treeitem', { name: '작업 Gate D 승인' })
    fireEvent.click(artifact)

    expect(window.location.search).toContain('selectedArtifactType=TASK')
    expect(window.location.search).toContain('selectedArtifactId=task-1')
    expect(window.location.search).toContain('sourceIterationId=source-iteration-1')
    expect(screen.getByRole('treeitem', { current: true, name: '작업 Gate D 승인' })).toBeTruthy()

    browse.unmount()
    setLocation('/artifact/TASK/task-1')
    render(
      <BrowserRouter>
        <Routes>
          <Route element={<ArtifactRouteSlot artifactClient={apiClient} />} path="/artifact/:artifactType/:artifactId" />
        </Routes>
      </BrowserRouter>,
    )

    expect(await screen.findByRole('heading', { name: 'Gate D 승인' })).toBeTruthy()
    expect(selections).toEqual([
      { artifactId: 'task-1', artifactType: 'TASK' },
      { artifactId: 'task-1', artifactType: 'TASK' },
    ])
  })
})

function artifactLookup(): ArtifactLookupItem {
  return {
    artifactId: 'task-1',
    artifactType: 'TASK',
    contentHash: null,
    createdAt: null,
    iterationId: 'iteration-1',
    lineage: {
      contentHash: null,
      iterationId: 'iteration-1',
      projectId: 'project-1',
      runId: null,
      snapshotVersion: null,
      sourcePath: null,
      taskId: 'task-1',
    },
    metadata: {},
    projectId: 'project-1',
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
    taskId: 'task-1',
    title: 'Gate D 승인',
    updatedAt: null,
  }
}

function artifactDetail(artifactId: string, artifactType: DashboardArtifactType): ArtifactDetail {
  return {
    artifactId,
    artifactType,
    createdAt: null,
    iterationId: 'iteration-1',
    lineage: {
      artifactRefs: [],
      contentHash: null,
      documentId: null,
      iterationId: 'iteration-1',
      projectId: 'project-1',
      runId: null,
      snapshotVersion: null,
      taskGraphId: null,
      taskId: artifactId,
    },
    mediaType: 'application/json',
    metadata: {},
    projectId: 'project-1',
    rawContent: '{}',
    source: {
      sourceDocumentId: null,
      sourceIterationId: null,
      sourcePath: null,
      sourceProjectId: null,
      sourceReference: null,
      sourceRunId: null,
      sourceTaskGraphId: null,
      sourceTaskId: null,
    },
    title: 'Gate D 승인',
    updatedAt: null,
  }
}
