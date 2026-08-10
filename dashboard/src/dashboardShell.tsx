import { AppShell } from '@astryxdesign/core/AppShell'
import { NavIcon } from '@astryxdesign/core/NavIcon'
import { SideNav, SideNavHeading, SideNavItem, SideNavSection } from '@astryxdesign/core/SideNav'
import { Text } from '@astryxdesign/core/Text'
import { TopNav, TopNavHeading } from '@astryxdesign/core/TopNav'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { Outlet, useLocation, useNavigate, useParams } from 'react-router-dom'
import { ArtifactTree, type ArtifactTreeSelection } from './artifact-tree'
import { ArtifactViewer } from './artifact-viewer'
import { dashboardApi, type DashboardApiClient, type DashboardArtifactType } from './data-access'
import { ErrorState } from './dashboardState'
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
    <nav aria-label="Gate 진행 상태" className="gate-progress-strip">
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

function WorkbenchFrame() {
  return (
    <div className="dashboard-workbench" data-testid="dashboard-workbench">
      <aside aria-label="문서 탐색" className="dashboard-workbench__pane dashboard-workbench__pane--library" data-testid="workbench-library">
        <div className="dashboard-workbench__pane-heading">
          <p>문서 탐색</p>
          <span>우선순위</span>
        </div>
        <div className="dashboard-workbench__placeholder">
          <strong>핵심 P2A 문서</strong>
          <p>선택한 프로젝트와 이터레이션의 문서가 이 영역에서 먼저 정리됩니다.</p>
        </div>
      </aside>
      <div className="dashboard-workbench__content" data-testid="workbench-content">
        <Outlet />
      </div>
      <aside aria-label="실행 맥락" className="dashboard-workbench__pane dashboard-workbench__pane--context" data-testid="workbench-context">
        <div className="dashboard-workbench__pane-heading">
          <p>실행 맥락</p>
          <span>읽기 전용</span>
        </div>
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
      </aside>
    </div>
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
    <RouteSlot
      description="프로젝트, 이터레이션, 산출물 계층이 이 영역에 순서대로 표시됩니다."
      title="산출물 탐색"
    >
      <QueryClientProvider client={queryClient}>
        <BrowseArtifactTree apiClient={apiClient} />
      </QueryClientProvider>
    </RouteSlot>
  )
}

function BrowseArtifactTree({ apiClient }: { readonly apiClient: DashboardApiClient }) {
  const location = useLocation()
  const navigate = useNavigate()
  const selectedArtifact = selectionFromBrowseSearch(new URLSearchParams(location.search))

  function selectArtifact(selection: ArtifactTreeSelection) {
    navigate({ pathname: '/browse', search: browseSearchForSelection(selection) })
  }

  return (
    <ArtifactTree
      apiClient={apiClient}
      onArtifactSelect={selectArtifact}
      selectedArtifact={selectedArtifact}
    />
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
  }
}

function browseSearchForSelection(selection: ArtifactTreeSelection) {
  const params = new URLSearchParams({
    projectId: selection.projectId,
    selectedArtifactId: selection.artifactId,
    selectedArtifactType: selection.artifactType,
  })
  if (selection.iterationId !== null) {
    params.set('iterationId', selection.iterationId)
  }
  return `?${params.toString()}`
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
