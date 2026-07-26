import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { BrowserRouter } from 'react-router-dom'
import { createDashboardApiClient } from '../data-access'
import { server } from '../test/mswServer'
import { SearchRoute } from './SearchRoute'
import {
  parseSearchUrl,
  searchInputKey,
  serializeSearchUrl,
  type SearchUrlState,
} from './searchUrlState'

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
  window.history.replaceState({}, '', '/')
})

describe('search URL state', () => {
  it('defaults invalid URLs to a keyword search and drops unbound cursors', () => {
    const state = parseSearchUrl(new URLSearchParams('q=%20%20&scope=unknown&filter=OTHER&mode=other&fusion=bad&page=4&cursor=old'))

    expect(state).toEqual({
      cursor: null,
      filter: null,
      fusion: null,
      mode: 'keyword',
      page: 1,
      q: '',
      scope: { kind: 'all' },
    })
    expect(serializeSearchUrl(state)).toBe('')
  })

  it('restores a scoped hybrid search only when its cursor key matches the effective inputs', () => {
    const inputs: SearchUrlState = {
      cursor: null,
      filter: 'TASK',
      fusion: { candidateLimit: 80, rrfK: 60 },
      mode: 'hybrid',
      page: 1,
      q: 'approval decision',
      scope: { kind: 'iteration', iterationId, projectId },
    }
    const params = new URLSearchParams({
      cursor: 'opaque-next-page',
      cursorKey: searchInputKey(inputs),
      filter: 'TASK',
      fusion: '80:60',
      iterationId,
      mode: 'hybrid',
      page: '2',
      projectId,
      q: 'approval decision',
      scope: 'iteration',
    })

    expect(parseSearchUrl(params)).toEqual({ ...inputs, cursor: 'opaque-next-page', page: 2 })

    const staleInputChanges: readonly { readonly name: string; readonly value: string }[] = [
      { name: 'q', value: 'other decision' },
      { name: 'scope', value: 'project' },
      { name: 'filter', value: 'RUN_RECORD' },
      { name: 'mode', value: 'semantic' },
      { name: 'fusion', value: '100:60' },
    ]
    for (const change of staleInputChanges) {
      const staleParams = new URLSearchParams(params)
      staleParams.set(change.name, change.value)
      const restored = parseSearchUrl(staleParams)
      expect(restored.cursor).toBeNull()
      expect(restored.page).toBe(1)
    }
  })
})

describe('SearchRoute', () => {
  it('restores a cursor-bound scoped URL into accessible controls and search navigation', async () => {
    const requestUrls: URL[] = []
    const restoredState: SearchUrlState = {
      cursor: 'bound-page-two',
      filter: 'TASK',
      fusion: null,
      mode: 'keyword',
      page: 2,
      q: 'restored decision',
      scope: { kind: 'iteration', iterationId, projectId },
    }
    server.use(
      http.get('/api/search/keyword', ({ request }) => {
        requestUrls.push(new URL(request.url))
        return HttpResponse.json(page([searchItem()]))
      }),
    )
    setLocation(`/search${serializeSearchUrl(restoredState)}`)
    renderSearch()

    expect(await screen.findByRole('link', { name: '부모 산출물 열기' })).toBeTruthy()
    expect(screen.getByLabelText('검색어')).toHaveProperty('value', 'restored decision')
    expect(screen.getByLabelText('검색 방식')).toHaveProperty('value', 'keyword')
    expect(screen.getByLabelText('범위')).toHaveProperty('value', 'iteration')
    expect(screen.getByLabelText('프로젝트 ID')).toHaveProperty('value', projectId)
    expect(screen.getByLabelText('이터레이션 ID')).toHaveProperty('value', iterationId)
    expect(screen.getByText('페이지 2')).toBeTruthy()
    expect(requestUrls).toHaveLength(1)
    expect(requestUrls[0]?.searchParams.get('cursor')).toBe('bound-page-two')
    expect(requestUrls[0]?.searchParams.get('artifactType')).toBe('TASK')
    expect(requestUrls[0]?.searchParams.get('projectId')).toBe(projectId)
    expect(requestUrls[0]?.searchParams.get('iterationId')).toBe(iterationId)
    expect(screen.getByRole('link', { name: '탐색에서 선택' }).getAttribute('href')).toBe(
      `/browse?projectId=${projectId}&selectedArtifactId=document-1&selectedArtifactType=DOCUMENT_SNAPSHOT&iterationId=${iterationId}`,
    )
  })

  it('keeps the default keyword mode explicit, preserves typed keyword parameters, and resets cursors for a new submission', async () => {
    const cursors: Array<string | null> = []
    server.use(
      http.get('/api/search/keyword', ({ request }) => {
        const url = new URL(request.url)
        cursors.push(url.searchParams.get('cursor'))
        return url.searchParams.get('cursor') === null
          ? HttpResponse.json(page([searchItem()], 'next-page'))
          : HttpResponse.json(page([searchItem({ chunkId: 'chunk-2', content: 'next page' })]))
      }),
    )
    setLocation('/search?q=first+decision')
    renderSearch()

    expect(await screen.findByRole('link', { name: '부모 산출물 열기' })).toHaveProperty('href', `${window.location.origin}/artifact/DOCUMENT_SNAPSHOT/document-1`)
    expect(screen.getByRole('link', { name: '탐색에서 선택' }).getAttribute('href')).toContain('selectedArtifactType=DOCUMENT_SNAPSHOT')
    expect(cursors).toEqual([null])

    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }))
    await screen.findByText('next page')
    expect(cursors).toEqual([null, 'next-page'])
    expect(window.location.search).toContain('cursor=next-page')
    expect(window.location.search).toContain('page=2')

    fireEvent.change(screen.getByLabelText('검색어'), { target: { value: 'second decision' } })
    fireEvent.click(screen.getByRole('button', { name: '검색' }))
    await waitFor(() => {
      expect(cursors).toEqual([null, 'next-page', null])
    })
    expect(window.location.search).not.toContain('cursor=')
    expect(window.location.search).not.toContain('page=')
  })

  it('uses the semantic endpoint and keeps provider-not-configured distinct without a keyword fallback', async () => {
    let keywordRequests = 0
    let semanticRequests = 0
    server.use(
      http.get('/api/search/keyword', () => {
        keywordRequests += 1
        return HttpResponse.json(page([]))
      }),
      http.post('/api/search/semantic', () => {
        semanticRequests += 1
        return HttpResponse.json(
          { error: 'embedding_provider_not_configured' },
          { status: 503 },
        )
      }),
    )
    setLocation('/search?q=related+decision&mode=semantic')
    renderSearch()

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('의미 검색 제공자가 구성되지 않았습니다')
    expect(alert.textContent).toContain('키워드 검색으로 자동 전환하지 않았습니다')
    expect(semanticRequests).toBe(1)
    expect(keywordRequests).toBe(0)
  })

  it('keeps provider-unavailable distinct without a keyword fallback', async () => {
    let keywordRequests = 0
    server.use(
      http.get('/api/search/keyword', () => {
        keywordRequests += 1
        return HttpResponse.json(page([]))
      }),
      http.post('/api/search/semantic', () => HttpResponse.json(
        { error: 'embedding_provider_unavailable' },
        { status: 503 },
      )),
    )
    setLocation('/search?q=related+decision&mode=semantic')
    renderSearch()

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('의미 검색 제공자를 사용할 수 없습니다')
    expect(alert.textContent).toContain('키워드 검색으로 자동 전환하지 않았습니다')
    expect(keywordRequests).toBe(0)
  })

  it('uses the hybrid endpoint with restored fusion parameters and shows unmapped chunks', async () => {
    let hybridBody: unknown = null
    server.use(
      http.post('/api/search/hybrid', async ({ request }) => {
        hybridBody = await request.json()
        return HttpResponse.json(page([searchItem({ documentId: null })]))
      }),
    )
    setLocation(`/search?q=approval&scope=iteration&projectId=${projectId}&iterationId=${iterationId}&mode=hybrid&fusion=80%3A60`)
    renderSearch()

    expect(await screen.findByRole('status', { name: '이 청크는 부모 산출물로 연결할 수 없습니다.' })).toBeTruthy()
    expect(hybridBody).toEqual({
      candidateLimit: 80,
      iterationId,
      limit: 20,
      projectId,
      q: 'approval',
      rrfK: 60,
    })
    expect(screen.queryByRole('link', { name: '부모 산출물 열기' })).toBeNull()
  })
})

function renderSearch() {
  return render(
    <BrowserRouter>
      <SearchRoute apiClient={createDashboardApiClient(browserFetch)} />
    </BrowserRouter>,
  )
}

function setLocation(pathname: string) {
  window.history.replaceState({}, '', pathname)
}

function page(items: readonly Record<string, unknown>[], nextCursor: string | null = null) {
  return { items, nextCursor }
}

interface SearchItemOverrides {
  readonly chunkId?: string | null
  readonly content?: string
  readonly documentId?: string | null
}

function searchItem({
  chunkId = 'chunk-1',
  content = 'approval decision content',
  documentId = 'document-1',
}: SearchItemOverrides = {}): Record<string, unknown> {
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
        sourcePath: 'docs/approval.md',
      },
      sourceIds: sourceIds(),
      sourceReference: null,
    },
    content,
    documentId,
    iterationId,
    lineage: {
      chunkId,
      chunkIndex: 0,
      documentId,
      iterationId,
      projectId,
      sourcePath: 'docs/approval.md',
    },
    matchReason: 'content',
    metadata: {},
    projectId,
    score: 1,
    sourceIds: sourceIds(),
    sourcePath: 'docs/approval.md',
    sourceReference: null,
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
