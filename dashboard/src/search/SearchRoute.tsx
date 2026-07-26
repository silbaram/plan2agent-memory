import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type ChangeEvent, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import {
  createDashboardQueries,
  dashboardApi,
  isDashboardApiError,
  type DashboardApiClient,
  type DashboardArtifactType,
  type HybridSearchItem,
  type HybridSearchRequest,
  type KeywordSearchItem,
  type KeywordSearchRequest,
  type Page,
  type SearchItemBase,
  type SemanticSearchItem,
  type SemanticSearchRequest,
} from '../data-access'
import {
  artifactTypeOptions,
  formStateFromSearchUrl,
  parseSearchUrl,
  searchUrlFromForm,
  serializeSearchUrl,
  validationMessageForForm,
  type SearchFormState,
  type SearchMode,
  type SearchUrlState,
} from './searchUrlState'
import './search.css'

type SearchItem = KeywordSearchItem | SemanticSearchItem | HybridSearchItem

const SEARCH_PAGE_SIZE = 20
const EMPTY_QUERY_PLACEHOLDER = 'search'

interface SearchRouteProps {
  readonly apiClient?: DashboardApiClient
}

interface ParentArtifact {
  readonly artifactId: string
  readonly artifactType: DashboardArtifactType
}

interface SearchRequestBundle {
  readonly hybrid: HybridSearchRequest
  readonly keyword: KeywordSearchRequest
  readonly semantic: SemanticSearchRequest
}

export function SearchRoute({ apiClient = dashboardApi }: SearchRouteProps) {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: {
        gcTime: 0,
        retry: false,
      },
    },
  }))

  return (
    <QueryClientProvider client={queryClient}>
      <SearchScreen apiClient={apiClient} />
    </QueryClientProvider>
  )
}

function SearchScreen({ apiClient }: { readonly apiClient: DashboardApiClient }) {
  const location = useLocation()
  const navigate = useNavigate()
  const state = useMemo(() => parseSearchUrl(new URLSearchParams(location.search)), [location.search])
  const canonicalSearch = useMemo(() => serializeSearchUrl(state), [state])
  const queries = useMemo(() => createDashboardQueries(apiClient), [apiClient])
  const requests = useMemo(() => searchRequestsFor(state), [state])
  const hasQuery = state.q.length > 0
  const keywordQuery = useQuery({
    ...queries.keywordSearch(requests.keyword),
    enabled: hasQuery && state.mode === 'keyword',
    retry: false,
  })
  const semanticQuery = useQuery({
    ...queries.semanticSearch(requests.semantic),
    enabled: hasQuery && state.mode === 'semantic',
    retry: false,
  })
  const hybridQuery = useQuery({
    ...queries.hybridSearch(requests.hybrid),
    enabled: hasQuery && state.mode === 'hybrid',
    retry: false,
  })
  const activeQuery = state.mode === 'keyword'
    ? keywordQuery
    : state.mode === 'semantic'
      ? semanticQuery
      : hybridQuery

  useEffect(() => {
    if (canonicalSearch !== location.search) {
      navigate({ pathname: location.pathname, search: canonicalSearch }, { replace: true })
    }
  }, [canonicalSearch, location.pathname, location.search, navigate])

  function submitSearch(form: SearchFormState) {
    const nextState = searchUrlFromForm(form)
    if (nextState !== null) {
      navigate({ pathname: '/search', search: serializeSearchUrl(nextState) })
    }
  }

  function goToNextPage() {
    const nextCursor = activeQuery.data?.nextCursor
    if (nextCursor === null || nextCursor === undefined) {
      return
    }
    const nextState: SearchUrlState = {
      ...state,
      cursor: nextCursor,
      page: state.page + 1,
    }
    navigate({ pathname: '/search', search: serializeSearchUrl(nextState) })
  }

  return (
    <section aria-label="산출물 검색" className="search-route">
      <SearchForm initialForm={formStateFromSearchUrl(state)} key={location.search} onSearch={submitSearch} />
      <SearchResults
        error={activeQuery.error}
        isLoading={activeQuery.isPending}
        mode={state.mode}
        onNextPage={goToNextPage}
        page={state.page}
        pageData={activeQuery.data}
        q={state.q}
      />
    </section>
  )
}

interface SearchFormProps {
  readonly initialForm: SearchFormState
  readonly onSearch: (form: SearchFormState) => void
}

function SearchForm({ initialForm, onSearch }: SearchFormProps) {
  const [form, setForm] = useState(initialForm)
  const [validationMessage, setValidationMessage] = useState<string | null>(null)

  function updateForm(event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) {
    const { name, value } = event.target
    switch (name) {
      case 'q':
        setForm((current) => ({ ...current, q: value }))
        return
      case 'filter':
        setForm((current) => ({ ...current, filter: value }))
        return
      case 'projectId':
        setForm((current) => ({ ...current, projectId: value }))
        return
      case 'iterationId':
        setForm((current) => ({ ...current, iterationId: value }))
        return
      case 'candidateLimit':
        setForm((current) => ({ ...current, candidateLimit: value }))
        return
      case 'rrfK':
        setForm((current) => ({ ...current, rrfK: value }))
        return
      case 'mode':
        if (isSearchMode(value)) {
          setForm((current) => ({ ...current, mode: value }))
        }
        return
      case 'scopeKind':
        if (isScopeKind(value)) {
          setForm((current) => ({ ...current, scopeKind: value }))
        }
        return
      default:
        return
    }
  }

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const message = validationMessageForForm(form)
    if (message !== null) {
      setValidationMessage(message)
      return
    }
    setValidationMessage(null)
    onSearch(form)
  }

  return (
    <>
      <form className="search-route__form" onSubmit={submitSearch}>
        <div className="search-route__field search-route__field--query">
          <label htmlFor="search-q">검색어</label>
          <input
            autoComplete="off"
            id="search-q"
            name="q"
            onChange={updateForm}
            placeholder="결정, 파일 경로 또는 작업을 검색하세요"
            type="search"
            value={form.q}
          />
        </div>
        <div className="search-route__field">
          <label htmlFor="search-mode">검색 방식</label>
          <select id="search-mode" name="mode" onChange={updateForm} value={form.mode}>
            <option value="keyword">키워드</option>
            <option value="semantic">의미</option>
            <option value="hybrid">혼합</option>
          </select>
        </div>
        <div className="search-route__field">
          <label htmlFor="search-scope">범위</label>
          <select id="search-scope" name="scopeKind" onChange={updateForm} value={form.scopeKind}>
            <option value="all">모든 프로젝트</option>
            <option value="project">프로젝트</option>
            <option value="iteration">이터레이션</option>
          </select>
        </div>
        <div className="search-route__field">
          <label htmlFor="search-filter">산출물 필터</label>
          <select id="search-filter" name="filter" onChange={updateForm} value={form.filter}>
            <option value="">모든 유형</option>
            {artifactTypeOptions.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>
        {form.scopeKind === 'all' ? null : (
          <div className="search-route__field">
            <label htmlFor="search-project-id">프로젝트 ID</label>
            <input id="search-project-id" name="projectId" onChange={updateForm} type="text" value={form.projectId} />
          </div>
        )}
        {form.scopeKind !== 'iteration' ? null : (
          <div className="search-route__field">
            <label htmlFor="search-iteration-id">이터레이션 ID</label>
            <input id="search-iteration-id" name="iterationId" onChange={updateForm} type="text" value={form.iterationId} />
          </div>
        )}
        {form.mode !== 'hybrid' ? null : (
          <fieldset className="search-route__fusion">
            <legend>혼합 점수 설정</legend>
            <div className="search-route__field">
              <label htmlFor="search-candidate-limit">후보 수</label>
              <input id="search-candidate-limit" inputMode="numeric" min={SEARCH_PAGE_SIZE} name="candidateLimit" onChange={updateForm} type="number" value={form.candidateLimit} />
            </div>
            <div className="search-route__field">
              <label htmlFor="search-rrf-k">RRF k</label>
              <input id="search-rrf-k" inputMode="numeric" min="1" name="rrfK" onChange={updateForm} type="number" value={form.rrfK} />
            </div>
          </fieldset>
        )}
        <button type="submit">검색</button>
      </form>
      {validationMessage === null ? null : <p className="search-route__validation" role="alert">{validationMessage}</p>}
    </>
  )
}

function searchRequestsFor(state: SearchUrlState): SearchRequestBundle {
  const base: KeywordSearchRequest = {
    ...scopeRequest(state),
    ...(state.filter === null ? {} : { artifactType: state.filter }),
    ...(state.cursor === null ? {} : { cursor: state.cursor }),
    limit: SEARCH_PAGE_SIZE,
    q: state.q.length > 0 ? state.q : EMPTY_QUERY_PLACEHOLDER,
  }

  return {
    hybrid: {
      ...base,
      ...(state.fusion === null ? {} : {
        candidateLimit: state.fusion.candidateLimit,
        rrfK: state.fusion.rrfK,
      }),
    },
    keyword: base,
    semantic: base,
  }
}

function scopeRequest(state: SearchUrlState): Pick<KeywordSearchRequest, 'iterationId' | 'projectId'> {
  switch (state.scope.kind) {
    case 'all':
      return {}
    case 'project':
      return { projectId: state.scope.projectId }
    case 'iteration':
      return { iterationId: state.scope.iterationId, projectId: state.scope.projectId }
  }
}

interface SearchResultsProps {
  readonly error: Error | null
  readonly isLoading: boolean
  readonly mode: SearchMode
  readonly onNextPage: () => void
  readonly page: number
  readonly pageData: Page<SearchItem> | undefined
  readonly q: string
}

function SearchResults({ error, isLoading, mode, onNextPage, page, pageData, q }: SearchResultsProps) {
  if (q.length === 0) {
    return (
      <section aria-label="검색 시작 안내" className="search-route__state" role="status">
        검색어와 방식을 선택한 뒤 검색하세요. 기본 방식은 키워드 검색입니다.
      </section>
    )
  }
  if (isLoading) {
    return <section aria-busy="true" className="search-route__state" role="status">검색 결과를 불러오는 중입니다.</section>
  }
  if (error !== null) {
    return <SearchError error={error} mode={mode} />
  }
  if (pageData === undefined || pageData.items.length === 0) {
    return <section aria-label="검색 결과 없음" className="search-route__state" role="status">일치하는 검색 결과가 없습니다.</section>
  }

  return (
    <section aria-label="검색 결과" className="search-route__results">
      <header className="search-route__results-header">
        <h2>검색 결과</h2>
        <p>페이지 {page}</p>
      </header>
      <ol className="search-route__result-list">
        {pageData.items.map((item, index) => (
          <SearchResultCard item={item} key={searchItemKey(item, index)} mode={mode} />
        ))}
      </ol>
      {pageData.nextCursor === null ? null : (
        <button onClick={onNextPage} type="button">다음 페이지</button>
      )}
    </section>
  )
}

function SearchError({ error, mode }: { readonly error: Error; readonly mode: SearchMode }) {
  if (isDashboardApiError(error) && error.code === 'embedding_provider_not_configured') {
    return (
      <section className="search-route__error" role="alert">
        <h2>의미 검색 제공자가 구성되지 않았습니다</h2>
        <p>의미 및 혼합 검색은 임베딩 제공자 설정이 필요합니다. 키워드 검색으로 자동 전환하지 않았습니다.</p>
      </section>
    )
  }
  if (isDashboardApiError(error) && error.code === 'embedding_provider_unavailable') {
    return (
      <section className="search-route__error" role="alert">
        <h2>의미 검색 제공자를 사용할 수 없습니다</h2>
        <p>의미 및 혼합 검색 제공자가 현재 준비되지 않았습니다. 키워드 검색으로 자동 전환하지 않았습니다.</p>
      </section>
    )
  }

  return (
    <section className="search-route__error" role="alert">
      <h2>{modeLabel(mode)} 검색을 완료할 수 없습니다</h2>
      <p>검색 조건을 확인한 뒤 다시 시도하세요.</p>
    </section>
  )
}

function SearchResultCard({ item, mode }: { readonly item: SearchItem; readonly mode: SearchMode }) {
  const parentArtifact = resolveParentArtifact(item)

  return (
    <li>
      <article className="search-route__result">
        <header>
          <p className="search-route__result-type">{item.artifactType}</p>
          <p>점수 {item.score}</p>
        </header>
        <p className="search-route__result-content">{item.content}</p>
        <dl className="search-route__result-metadata">
          <div><dt>문서 ID</dt><dd>{item.documentId ?? '—'}</dd></div>
          <div><dt>청크</dt><dd>{item.chunkIndex === null ? '—' : item.chunkIndex}</dd></div>
          <div><dt>경로</dt><dd>{item.sourcePath ?? '—'}</dd></div>
        </dl>
        <SearchModeMetadata item={item} mode={mode} />
        {parentArtifact === null ? (
          <p aria-label="이 청크는 부모 산출물로 연결할 수 없습니다." className="search-route__unmapped" role="status">
            이 청크는 부모 산출물로 연결할 수 없습니다.
          </p>
        ) : (
          <p className="search-route__links">
            <Link to={artifactHref(parentArtifact)}>부모 산출물 열기</Link>
            <Link to={browseHref(parentArtifact, item)}>탐색에서 선택</Link>
          </p>
        )}
      </article>
    </li>
  )
}

function SearchModeMetadata({ item, mode }: { readonly item: SearchItem; readonly mode: SearchMode }) {
  if (mode === 'keyword' && 'matchReason' in item) {
    return <p>일치 근거: {item.matchReason}</p>
  }
  if (mode === 'semantic' && 'distanceMetric' in item) {
    return <p>거리: {item.distanceMetric} · {item.embeddingModel} · {item.embeddingVersion}</p>
  }
  if (mode === 'hybrid' && 'keyword' in item) {
    return (
      <p>
        키워드 순위: {item.keyword?.rank ?? '—'} · 의미 순위: {item.vector?.rank ?? '—'}
      </p>
    )
  }
  return null
}

function resolveParentArtifact(item: SearchItemBase): ParentArtifact | null {
  if (item.documentId === null) {
    return null
  }
  if (item.artifactType === 'DOCUMENT_CHUNK' || item.artifactType === 'DOCUMENT_SNAPSHOT') {
    return { artifactId: item.documentId, artifactType: 'DOCUMENT_SNAPSHOT' }
  }
  return null
}

function artifactHref(parentArtifact: ParentArtifact) {
  return `/artifact/${encodeURIComponent(parentArtifact.artifactType)}/${encodeURIComponent(parentArtifact.artifactId)}`
}

function browseHref(parentArtifact: ParentArtifact, item: SearchItemBase) {
  const params = new URLSearchParams({
    projectId: item.projectId,
    selectedArtifactId: parentArtifact.artifactId,
    selectedArtifactType: parentArtifact.artifactType,
  })
  if (item.iterationId !== null) {
    params.set('iterationId', item.iterationId)
  }
  return `/browse?${params.toString()}`
}

function searchItemKey(item: SearchItemBase, index: number) {
  return [item.chunkId ?? '', item.documentId ?? '', item.sourcePath ?? '', item.chunkIndex ?? '', index].join(':')
}

function modeLabel(mode: SearchMode) {
  switch (mode) {
    case 'keyword':
      return '키워드'
    case 'semantic':
      return '의미'
    case 'hybrid':
      return '혼합'
  }
}

function isSearchMode(value: string): value is SearchMode {
  return value === 'keyword' || value === 'semantic' || value === 'hybrid'
}

function isScopeKind(value: string): value is SearchUrlState['scope']['kind'] {
  return value === 'all' || value === 'project' || value === 'iteration'
}
