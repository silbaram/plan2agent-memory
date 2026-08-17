import { AppShell } from '@astryxdesign/core/AppShell'
import { NavIcon } from '@astryxdesign/core/NavIcon'
import { SideNav, SideNavHeading, SideNavItem, SideNavSection } from '@astryxdesign/core/SideNav'
import { Text } from '@astryxdesign/core/Text'
import { TopNav, TopNavHeading } from '@astryxdesign/core/TopNav'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useReducer, useState, type ReactNode } from 'react'
import { Outlet, useLocation, useNavigate, useParams } from 'react-router-dom'
import { ArtifactTree, type ArtifactTreeSelection } from './artifact-tree'
import {
  ArtifactViewer,
  classifyPriorityDocuments,
  deriveGateProgressItem,
  deriveLineageWork,
  type ArtifactIdentity,
  type PriorityDocument,
  type PriorityDocumentClassification,
  type PriorityDocumentKind,
  type PriorityDocumentLookupScope,
} from './artifact-viewer'
import {
  createDashboardQueries,
  dashboardApi,
  type ArtifactDetail,
  type ArtifactLookupItem,
  type DashboardApiClient,
  type DashboardArtifactType,
} from './data-access'
import { EmptyStatePanel, ErrorState, LoadingState } from './dashboardState'
import { SearchRoute } from './search'
import { TraceRoute } from './trace'

interface NavigationItem {
  readonly href: string
  readonly label: string
}

interface RouteSlotProps {
  readonly children: ReactNode
  readonly description: string
  readonly title: string
}

interface BrowseRouteSlotProps {
  readonly apiClient?: DashboardApiClient
}

interface ArtifactRouteSlotProps {
  readonly artifactClient?: Pick<DashboardApiClient, 'getArtifact'>
}

interface BrowseScope {
  readonly iterationId: string
  readonly projectId: string
  readonly sourceIterationId: string
}

interface BrowseExecutionContextProps {
  readonly artifact: ArtifactDetail | null
  readonly classification: PriorityDocumentClassification | null
  readonly selectedArtifact: ArtifactTreeSelection | null
  readonly workArtifacts: readonly ArtifactLookupItem[]
}

interface WorkbenchContextPaneProps {
  readonly children: ReactNode
}

interface ContextDisclosureState {
  readonly isCompact: boolean
  readonly isExpanded: boolean
}

type ContextDisclosureAction =
  | { readonly type: 'media-change'; readonly isCompact: boolean }
  | { readonly type: 'toggle' }

const navigationItems: readonly NavigationItem[] = [
  { href: '/browse', label: '탐색' },
  { href: '/search', label: '검색' },
  { href: '/trace', label: '추적' },
]

const gateProgress: readonly { readonly label: string; readonly state: 'complete' | 'current' | 'upcoming' }[] = [
  { label: 'Intake', state: 'complete' },
  { label: '제품 명세', state: 'complete' },
  { label: '구현 계획', state: 'complete' },
  { label: 'Task graph', state: 'current' },
  { label: '실행 검토', state: 'upcoming' },
]

const disabledBrowseScope: PriorityDocumentLookupScope = {
  iterationId: 'disabled-iteration',
  projectId: 'disabled-project',
  sourceIterationId: 'disabled-source-iteration',
}

const BROWSE_CURRENT_PAGE_LIMIT = 50
const compactWorkbenchMediaQuery = '(max-width: 70rem)'

const browseArtifactTypes: readonly DashboardArtifactType[] = [
  'DOCUMENT_SNAPSHOT',
  'TASK_GRAPH',
  'TASK',
  'RUN_RECORD',
  'PROPOSAL',
]

const contextGateDefinitions: readonly {
  readonly canonicalKind: PriorityDocumentKind
  readonly gate: string
  readonly label: string
}[] = [
  { canonicalKind: 'product_spec', gate: 'Gate B', label: '제품 명세' },
  { canonicalKind: 'task_graph', gate: 'Gate C', label: 'Task graph' },
  { canonicalKind: 'final_review', gate: 'Gate D', label: '실행 검토' },
]

function isCurrentNavigationRoute(pathname: string, href: string) {
  if (href === '/browse') {
    return pathname === '/' || pathname === href
  }

  return pathname === href || pathname.startsWith(`${href}/`)
}

function RouteSlot({ children, description, title }: RouteSlotProps) {
  return (
    <div className="route-slot" id="main-content" tabIndex={-1}>
      <div className="route-slot__header">
        <Text as="h1" type="display-2">
          {title}
        </Text>
        <Text as="p" color="secondary" type="large">
          {description}
        </Text>
      </div>
      {children}
    </div>
  )
}

function DashboardNavigation() {
  const location = useLocation()

  return (
    <SideNav
      aria-label="주요 탐색"
      className="dashboard-rail"
      footer={
        <Text as="p" color="primary" type="supporting">
          서버 데이터를 변경하지 않는 읽기 전용 화면입니다.
        </Text>
      }
      header={
        <SideNavHeading
          heading="Plan2Agent Memory"
          headingHref="/browse"
          icon={<NavIcon icon={<span aria-hidden="true">P</span>} />}
        />
      }
    >
      <SideNavSection isHeaderHidden title="조회">
        {navigationItems.map((item) => (
          <SideNavItem
            href={item.href}
            isSelected={isCurrentNavigationRoute(location.pathname, item.href)}
            key={item.href}
            label={item.label}
          />
        ))}
      </SideNavSection>
    </SideNav>
  )
}

function GateProgressStrip() {
  return (
    <nav aria-label="Gate 진행 상태" className="gate-progress-strip" tabIndex={0}>
      <ol>
        {gateProgress.map((gate, index) => (
          <li aria-current={gate.state === 'current' ? 'step' : undefined} data-state={gate.state} key={gate.label}>
            <span>G{index + 1}</span>
            <strong>{gate.label}</strong>
            <small>{gate.state === 'complete' ? '확인됨' : gate.state === 'current' ? '진행 중' : '대기'}</small>
          </li>
        ))}
      </ol>
    </nav>
  )
}

function isBrowseRoute(pathname: string) {
  return pathname === '/' || pathname === '/browse'
}

function mediaQueryMatches(query: string) {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches
}

function createContextDisclosureState(): ContextDisclosureState {
  const isCompact = mediaQueryMatches(compactWorkbenchMediaQuery)
  return { isCompact, isExpanded: !isCompact }
}

function reduceContextDisclosure(state: ContextDisclosureState, action: ContextDisclosureAction): ContextDisclosureState {
  if (action.type === 'media-change') {
    return {
      isCompact: action.isCompact,
      isExpanded: !action.isCompact,
    }
  }

  return { ...state, isExpanded: !state.isExpanded }
}

function useContextDisclosure() {
  const [state, dispatch] = useReducer(reduceContextDisclosure, undefined, createContextDisclosureState)

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return undefined
    }

    const mediaQuery = window.matchMedia(compactWorkbenchMediaQuery)
    const updateDisclosure = (event: MediaQueryListEvent) => {
      dispatch({ isCompact: event.matches, type: 'media-change' })
    }

    mediaQuery.addEventListener('change', updateDisclosure)

    return () => {
      mediaQuery.removeEventListener('change', updateDisclosure)
    }
  }, [])

  return { state, toggle: () => dispatch({ type: 'toggle' }) }
}

function WorkbenchFrame() {
  const location = useLocation()

  return (
    <div className="dashboard-workbench" data-testid="dashboard-workbench">
      {isBrowseRoute(location.pathname) ? <Outlet /> : <StaticWorkbenchPanels />}
    </div>
  )
}

function StaticWorkbenchPanels() {
  return (
    <>
      <aside aria-label="문서 탐색" className="dashboard-workbench__pane dashboard-workbench__pane--library" data-testid="workbench-library">
        <div className="dashboard-workbench__pane-heading">
          <p>문서 탐색</p>
          <span>우선순위</span>
        </div>
        <div className="dashboard-workbench__placeholder">
          <strong>핵심 P2A 문서</strong>
          <p>탐색 화면에서 선택한 프로젝트와 이터레이션의 문서를 먼저 정리합니다.</p>
        </div>
      </aside>
      <div className="dashboard-workbench__content" data-testid="workbench-content">
        <Outlet />
      </div>
      <WorkbenchContextPane>
        <dl className="dashboard-workbench__context-list">
          <div>
            <dt>현재 단계</dt>
            <dd>Task graph</dd>
          </div>
          <div>
            <dt>다음 정보</dt>
            <dd>선택 문서의 연결 작업</dd>
          </div>
          <div>
            <dt>상태</dt>
            <dd>로컬 · 변경 없음</dd>
          </div>
        </dl>
      </WorkbenchContextPane>
    </>
  )
}

function WorkbenchContextPane({ children }: WorkbenchContextPaneProps) {
  const { state, toggle } = useContextDisclosure()
  const isCollapsed = state.isCompact && !state.isExpanded

  return (
    <aside
      aria-label="실행 맥락"
      className="dashboard-workbench__pane dashboard-workbench__pane--context"
      data-context-layout={state.isCompact ? 'disclosure' : 'panel'}
      data-testid="workbench-context"
    >
      <div className="dashboard-workbench__pane-heading dashboard-workbench__pane-heading--context">
        <p>실행 맥락</p>
        <span>읽기 전용</span>
        {state.isCompact ? (
          <button
            aria-controls="workbench-context-content"
            aria-expanded={state.isExpanded}
            className="dashboard-workbench__context-toggle"
            onClick={toggle}
            type="button"
          >
            {state.isExpanded ? '맥락 접기' : '맥락 펼치기'}
          </button>
        ) : null}
      </div>
      <div className="dashboard-workbench__context-content" hidden={isCollapsed} id="workbench-context-content">
        {children}
      </div>
    </aside>
  )
}

export function DashboardShell() {
  return (
    <AppShell
      className="dashboard-shell"
      contentPadding={0}
      height="auto"
      mobileNav={{ breakpoint: 'md' }}
      sideNav={<DashboardNavigation />}
      topNav={
        <TopNav
          endContent={
            <span className="dashboard-topbar__status">
              <span aria-hidden="true" className="dashboard-topbar__status-dot" />
              local · 읽기 전용
            </span>
          }
          heading={<TopNavHeading heading="Plan2Agent Memory" headingHref="/browse" />}
          label="대시보드 탐색"
          startContent={
            <span className="dashboard-topbar__scope">
              현재 작업공간 <span>Plan2Agent / Memory</span>
            </span>
          }
        />
      }
      variant="elevated"
    >
      <GateProgressStrip />
      <WorkbenchFrame />
    </AppShell>
  )
}

export function BrowseRouteSlot({ apiClient = dashboardApi }: BrowseRouteSlotProps) {
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
      <BrowseWorkbench apiClient={apiClient} />
    </QueryClientProvider>
  )
}

function BrowseWorkbench({ apiClient }: { readonly apiClient: DashboardApiClient }) {
  const location = useLocation()
  const navigate = useNavigate()
  const searchParams = useMemo(() => new URLSearchParams(location.search), [location.search])
  const selectedArtifact = useMemo(() => selectionFromBrowseSearch(searchParams), [searchParams])
  const scope = useMemo(() => browseScopeFromSearch(searchParams), [searchParams])
  const queries = useMemo(() => createDashboardQueries(apiClient), [apiClient])
  const priorityLookup = useQuery({
    ...queries.priorityDocumentLookup(scope ?? disabledBrowseScope),
    enabled: scope !== null,
    retry: false,
  })
  const currentArtifacts = useQuery({
    ...queries.artifacts(scope === null ? {} : currentBrowsePageRequest(scope, searchParams)),
    enabled: scope !== null,
    retry: false,
  })
  const classification = useMemo(() => {
    if (scope === null || priorityLookup.data === undefined) {
      return null
    }
    return classifyPriorityDocuments({
      currentPage: currentArtifacts.data,
      lookup: priorityLookup.data,
      scope,
      selection: selectionIdentity(selectedArtifact),
    })
  }, [currentArtifacts.data, priorityLookup.data, scope, selectedArtifact])
  const workArtifacts = useMemo(
    () => currentArtifacts.data?.items.filter(isWorkArtifact) ?? [],
    [currentArtifacts.data],
  )
  const [loadedArtifact, setLoadedArtifact] = useState<ArtifactDetail | null>(null)
  const visibleArtifact = matchesSelectedArtifact(loadedArtifact, selectedArtifact) ? loadedArtifact : null

  function selectArtifact(selection: ArtifactTreeSelection) {
    navigate({
      pathname: '/browse',
      search: browseSearchForSelection(searchParams, selection),
    })
  }

  const onArtifactLoad = useCallback((artifact: ArtifactDetail | null) => {
    setLoadedArtifact(artifact)
  }, [])

  return (
    <>
      <aside aria-label="문서 탐색" className="dashboard-workbench__pane dashboard-workbench__pane--library" data-testid="workbench-library">
        <div className="dashboard-workbench__pane-heading">
          <p>문서 탐색</p>
          <span>우선순위</span>
        </div>
        <div className="browse-workbench__library-content">
          <BrowsePriorityDocuments
            classification={classification}
            currentArtifactsError={currentArtifacts.isError}
            isLoading={scope !== null && (priorityLookup.isPending || currentArtifacts.isPending)}
            lookupError={priorityLookup.isError}
            onRetry={() => {
              void priorityLookup.refetch()
              void currentArtifacts.refetch()
            }}
            onSelect={selectArtifact}
            scope={scope}
            selectedArtifact={selectedArtifact}
          />
          <section aria-labelledby="browse-artifact-tree-heading" className="browse-workbench__tree">
            <h2 id="browse-artifact-tree-heading">기타 산출물 계층</h2>
            <ArtifactTree
              apiClient={apiClient}
              onArtifactSelect={selectArtifact}
              selectedArtifact={selectedArtifact}
            />
          </section>
        </div>
      </aside>
      <div className="dashboard-workbench__content" data-testid="workbench-content">
        <RouteSlot
          description="핵심 문서와 관련 산출물을 선택하면 안전한 읽기 전용 본문을 이 영역에서 확인할 수 있습니다."
          title="산출물 탐색"
        >
          {selectedArtifact === null ? (
            <EmptyStatePanel
              description="왼쪽 핵심 문서 또는 산출물 계층에서 문서를 선택하세요."
              title="읽을 문서를 선택하세요"
            />
          ) : (
            <ArtifactViewer
              artifactId={selectedArtifact.artifactId}
              artifactType={selectedArtifact.artifactType}
              client={apiClient}
              onArtifactLoad={onArtifactLoad}
              workArtifacts={workArtifacts}
            />
          )}
        </RouteSlot>
      </div>
      <WorkbenchContextPane>
        <BrowseExecutionContext
          artifact={visibleArtifact}
          classification={classification}
          selectedArtifact={selectedArtifact}
          workArtifacts={workArtifacts}
        />
      </WorkbenchContextPane>
    </>
  )
}

interface BrowsePriorityDocumentsProps {
  readonly classification: PriorityDocumentClassification | null
  readonly currentArtifactsError: boolean
  readonly isLoading: boolean
  readonly lookupError: boolean
  readonly onRetry: () => void
  readonly onSelect: (selection: ArtifactTreeSelection) => void
  readonly scope: BrowseScope | null
  readonly selectedArtifact: ArtifactTreeSelection | null
}

function BrowsePriorityDocuments({
  classification,
  currentArtifactsError,
  isLoading,
  lookupError,
  onRetry,
  onSelect,
  scope,
  selectedArtifact,
}: BrowsePriorityDocumentsProps) {
  if (scope === null) {
    return (
      <EmptyStatePanel
        description="프로젝트와 이터레이션을 선택하면 정해진 다섯 문서를 first page 범위에서만 확인합니다."
        title="핵심 문서 범위를 선택하세요"
      />
    )
  }
  if (isLoading) {
    return <LoadingState description="현재 이터레이션의 정확한 핵심 문서 경로만 조회하고 있습니다." title="핵심 문서를 불러오는 중" />
  }
  if (lookupError || currentArtifactsError) {
    return (
      <section className="browse-workbench__priority-state">
        <ErrorState
          description="핵심 문서 또는 현재 산출물 페이지를 불러오지 못했습니다. 기존 범위를 바꾸지 않고 다시 시도할 수 있습니다."
          title="핵심 문서를 표시할 수 없습니다"
        />
        <button onClick={onRetry} type="button">다시 시도</button>
      </section>
    )
  }
  if (classification === null || (classification.priorityDocuments.length === 0 && classification.supportingDocuments.length === 0)) {
    return (
      <EmptyStatePanel
        description="정확한 핵심 문서 경로와 현재 페이지에서 표시할 산출물을 찾지 못했습니다. 전체 corpus를 순회하지 않았습니다."
        title="표시할 문서가 없습니다"
      />
    )
  }

  return (
    <section aria-labelledby="priority-documents-heading" className="browse-workbench__priority-documents">
      <header>
        <h2 id="priority-documents-heading">핵심 P2A 문서</h2>
        <p>현재 이터레이션에서 우선순위 순으로 표시합니다.</p>
      </header>
      {classification.priorityDocuments.length === 0 ? (
        <p className="browse-workbench__notice" role="status">이 페이지에는 정확한 핵심 문서가 없습니다.</p>
      ) : (
        <ol className="browse-workbench__document-list">
          {classification.priorityDocuments.map((document) => (
            <PriorityDocumentItem
              document={document}
              key={artifactIdentityKey(document.identity)}
              onSelect={onSelect}
              scope={scope}
              selectedArtifact={selectedArtifact}
            />
          ))}
        </ol>
      )}
      {classification.supportingDocuments.length === 0 ? null : (
        <section aria-labelledby="supporting-documents-heading" className="browse-workbench__supporting-documents">
          <h3 id="supporting-documents-heading">관련 산출물</h3>
          <ol className="browse-workbench__document-list">
            {classification.supportingDocuments.map((artifact) => (
              <SupportingDocumentItem
                artifact={artifact}
                key={artifactIdentityKey(artifact)}
                onSelect={onSelect}
                selectedArtifact={selectedArtifact}
              />
            ))}
          </ol>
        </section>
      )}
    </section>
  )
}

function PriorityDocumentItem({ document, onSelect, scope, selectedArtifact }: {
  readonly document: PriorityDocument
  readonly onSelect: (selection: ArtifactTreeSelection) => void
  readonly scope: BrowseScope
  readonly selectedArtifact: ArtifactTreeSelection | null
}) {
  const isSelected = artifactIdentityMatchesSelection(document.identity, selectedArtifact)
  return (
    <li>
      <button
        aria-current={isSelected ? 'page' : undefined}
        className="browse-workbench__document-button"
        onClick={() => onSelect(selectionForPriorityDocument(document, scope))}
        type="button"
      >
        <span>{document.label}</span>
        <small>{document.gate} · {document.explicitStatus}</small>
        <strong>{document.purpose}</strong>
      </button>
    </li>
  )
}

function SupportingDocumentItem({ artifact, onSelect, selectedArtifact }: {
  readonly artifact: ArtifactLookupItem
  readonly onSelect: (selection: ArtifactTreeSelection) => void
  readonly selectedArtifact: ArtifactTreeSelection | null
}) {
  const artifactType = dashboardArtifactTypeFrom(artifact.artifactType)
  const isSelected = artifactType !== null && artifactIdentityMatchesSelection({ artifactId: artifact.artifactId, artifactType }, selectedArtifact)
  if (artifactType === null) {
    return (
      <li className="browse-workbench__unsupported-document">
        <span>{artifact.title}</span>
        <small>{artifact.artifactType}</small>
      </li>
    )
  }

  return (
    <li>
      <button
        aria-current={isSelected ? 'page' : undefined}
        className="browse-workbench__document-button"
        onClick={() => onSelect({
          artifactId: artifact.artifactId,
          artifactType,
          iterationId: artifact.iterationId,
          projectId: artifact.projectId,
          sourceIterationId: artifact.sourceIds.sourceIterationId ?? selectedArtifact?.sourceIterationId ?? null,
        })}
        type="button"
      >
        <span>{artifact.title}</span>
        <small>{artifact.artifactType} · {artifact.sourcePath ?? 'source path 없음'}</small>
      </button>
    </li>
  )
}

function BrowseExecutionContext({ artifact, classification, selectedArtifact, workArtifacts }: BrowseExecutionContextProps) {
  const gateProgress = gateProgressFor(classification, artifact)
  const lineageWork = artifact === null ? null : deriveLineageWork({ artifact, workArtifacts })

  return (
    <div className="browse-workbench__execution-context">
      <section aria-labelledby="browse-context-gates">
        <h2 id="browse-context-gates">Gate 상태</h2>
        <ol className="browse-workbench__gate-list">
          {gateProgress.map((gate) => (
            <li data-status={gate.explicitStatus} key={gate.gate}>
              <strong>{gate.gate}</strong>
              <span>{gate.label}</span>
              <small>{gate.explicitStatus}</small>
            </li>
          ))}
        </ol>
      </section>
      <section aria-labelledby="browse-context-selection">
        <h2 id="browse-context-selection">선택 문서</h2>
        {selectedArtifact === null ? (
          <p>선택한 문서가 없습니다.</p>
        ) : (
          <dl className="dashboard-workbench__context-list">
            <div>
              <dt>유형</dt>
              <dd>{selectedArtifact.artifactType}</dd>
            </div>
            <div>
              <dt>식별자</dt>
              <dd>{selectedArtifact.artifactId}</dd>
            </div>
          </dl>
        )}
      </section>
      <section aria-labelledby="browse-context-linked-work">
        <h2 id="browse-context-linked-work">연결 작업</h2>
        {lineageWork === null ? (
          <p>문서를 선택하면 확인된 계보의 task와 run만 표시합니다.</p>
        ) : lineageWork.availability === 'unavailable' ? (
          <p>연결 작업을 확인할 수 없습니다 ({lineageWork.reason}).</p>
        ) : lineageWork.linkedWork.length === 0 ? (
          <p>확인된 계보에 연결된 작업이 없습니다.</p>
        ) : (
          <ul className="browse-workbench__work-list">
            {lineageWork.linkedWork.map((work) => (
              <li key={artifactIdentityKey(work.identity)}>
                <span>{work.label}</span>
                <small>{work.status} · {work.relation}</small>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-labelledby="browse-context-recent-work">
        <h2 id="browse-context-recent-work">최근 완료 작업</h2>
        {lineageWork === null || lineageWork.availability === 'unavailable' || lineageWork.recentCompletedWork.length === 0 ? (
          <p>확인된 최근 완료 작업이 없습니다.</p>
        ) : (
          <ol className="browse-workbench__work-list">
            {lineageWork.recentCompletedWork.map(({ completedAt, result, work }) => (
              <li key={artifactIdentityKey(work.identity)}>
                <span>{work.label}</span>
                <small>{completedAt ?? '완료 시각 없음'} · {result ?? work.status}</small>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  )
}

export function ArtifactRouteSlot({ artifactClient }: ArtifactRouteSlotProps) {
  const { artifactId, artifactType } = useParams()

  return (
    <RouteSlot
      description="메타데이터, 안전한 미리보기와 원문 뷰어를 위한 읽기 전용 영역입니다."
      title="산출물 상세"
    >
      <ArtifactViewer artifactId={artifactId ?? ''} artifactType={artifactType ?? ''} client={artifactClient} />
    </RouteSlot>
  )
}

export function SearchRouteSlot() {
  return (
    <RouteSlot
      description="키워드, 의미, 혼합 검색 결과가 구분되어 이 영역에 표시됩니다."
      title="검색"
    >
      <SearchRoute />
    </RouteSlot>
  )
}

export function TraceRouteSlot() {
  return (
    <RouteSlot
      description="선택한 산출물의 노드와 간선 관계를 읽기 전용으로 확인하는 영역입니다."
      title="계보 추적"
    >
      <TraceRoute />
    </RouteSlot>
  )
}

export function NotFoundRouteSlot() {
  return (
    <RouteSlot description="주소를 확인한 뒤 탐색, 검색 또는 추적 화면으로 이동하세요." title="화면을 찾을 수 없습니다">
      <ErrorState
        description="요청한 읽기 전용 화면은 현재 대시보드 경로에 없습니다."
        title="잘못된 대시보드 경로"
      />
    </RouteSlot>
  )
}

function selectionFromBrowseSearch(params: URLSearchParams): ArtifactTreeSelection | null {
  const artifactType = dashboardArtifactTypeFrom(params.get('selectedArtifactType'))
  const artifactId = params.get('selectedArtifactId')?.trim()
  const projectId = params.get('projectId')?.trim()
  const iterationId = params.get('iterationId')?.trim()
  if (artifactType === null || artifactId === undefined || artifactId.length === 0 || projectId === undefined || projectId.length === 0) {
    return null
  }

  return {
    artifactId,
    artifactType,
    iterationId: iterationId === undefined || iterationId.length === 0 ? null : iterationId,
    projectId,
    sourceIterationId: normalizedSearchParameter(params, 'sourceIterationId'),
  }
}

function browseScopeFromSearch(params: URLSearchParams): BrowseScope | null {
  const projectId = normalizedSearchParameter(params, 'projectId')
  const iterationId = normalizedSearchParameter(params, 'iterationId')
  if (projectId === null || iterationId === null) {
    return null
  }

  return {
    iterationId,
    projectId,
    sourceIterationId: normalizedSearchParameter(params, 'sourceIterationId') ?? iterationId,
  }
}

function currentBrowsePageRequest(scope: BrowseScope, params: URLSearchParams) {
  const cursor = normalizedSearchParameter(params, 'cursor')
  return {
    ...(cursor === null ? {} : { cursor }),
    artifactTypes: browseArtifactTypes,
    iterationId: scope.iterationId,
    limit: BROWSE_CURRENT_PAGE_LIMIT,
    projectId: scope.projectId,
  }
}

function browseSearchForSelection(currentParams: URLSearchParams, selection: ArtifactTreeSelection) {
  const params = new URLSearchParams(currentParams)
  params.set('projectId', selection.projectId)
  params.set('selectedArtifactId', selection.artifactId)
  params.set('selectedArtifactType', selection.artifactType)
  if (selection.iterationId !== null) {
    params.set('iterationId', selection.iterationId)
  } else {
    params.delete('iterationId')
  }
  if (selection.sourceIterationId !== null) {
    params.set('sourceIterationId', selection.sourceIterationId)
  } else {
    params.delete('sourceIterationId')
  }
  return `?${params.toString()}`
}

function normalizedSearchParameter(params: URLSearchParams, key: string): string | null {
  const value = params.get(key)?.trim()
  return value === undefined || value.length === 0 ? null : value
}

function selectionIdentity(selection: ArtifactTreeSelection | null): ArtifactIdentity | null {
  return selection === null
    ? null
    : { artifactId: selection.artifactId, artifactType: selection.artifactType }
}

function selectionForPriorityDocument(document: PriorityDocument, scope: BrowseScope): ArtifactTreeSelection {
  return {
    artifactId: document.identity.artifactId,
    artifactType: document.identity.artifactType,
    iterationId: scope.iterationId,
    projectId: scope.projectId,
    sourceIterationId: scope.sourceIterationId,
  }
}

function artifactIdentityKey(identity: ArtifactIdentity | Pick<ArtifactLookupItem, 'artifactId' | 'artifactType'>): string {
  return `${identity.artifactType}\u0000${identity.artifactId}`
}

function artifactIdentityMatchesSelection(identity: ArtifactIdentity, selection: ArtifactTreeSelection | null): boolean {
  return selection !== null
    && identity.artifactId === selection.artifactId
    && identity.artifactType === selection.artifactType
}

function matchesSelectedArtifact(artifact: ArtifactDetail | null, selection: ArtifactTreeSelection | null): artifact is ArtifactDetail {
  return artifact !== null
    && selection !== null
    && artifact.artifactId === selection.artifactId
    && artifact.artifactType === selection.artifactType
}

function isWorkArtifact(artifact: ArtifactLookupItem): boolean {
  return artifact.artifactType === 'TASK' || artifact.artifactType === 'RUN_RECORD'
}

function gateProgressFor(classification: PriorityDocumentClassification | null, selectedArtifact: ArtifactDetail | null) {
  return contextGateDefinitions.map((definition) => {
    const document = classification?.priorityDocuments.find((candidate) => candidate.canonicalKind === definition.canonicalKind)
    const selectedGateArtifact = document !== undefined
      && selectedArtifact !== null
      && selectedArtifact.artifactType === document.identity.artifactType
      && selectedArtifact.artifactId === document.identity.artifactId
      ? selectedArtifact
      : null
    return deriveGateProgressItem({
      approvalAudit: selectedGateArtifact === null ? undefined : approvalAuditFor(selectedGateArtifact),
      artifact: selectedGateArtifact,
      explicitStatus: selectedGateArtifact?.metadata.status
        ?? (selectedGateArtifact === null && document !== undefined ? 'pending' : undefined),
      gate: definition.gate,
      isCurrent: selectedGateArtifact !== null,
      label: definition.label,
    })
  })
}

function approvalAuditFor(artifact: ArtifactDetail): unknown {
  const audit = artifact.metadata.approvalAudit ?? artifact.metadata.approval_audit
  if (audit === undefined) {
    return undefined
  }

  try {
    return JSON.parse(audit)
  } catch {
    return audit
  }
}

function dashboardArtifactTypeFrom(value: string | null): DashboardArtifactType | null {
  switch (value) {
    case 'DOCUMENT_SNAPSHOT':
    case 'TASK_GRAPH':
    case 'TASK':
    case 'RUN_RECORD':
    case 'PROPOSAL':
      return value
    default:
      return null
  }
}
