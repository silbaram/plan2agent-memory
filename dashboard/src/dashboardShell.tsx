import { AppShell } from '@astryxdesign/core/AppShell'
import { NavIcon } from '@astryxdesign/core/NavIcon'
import { SideNav, SideNavHeading, SideNavItem, SideNavSection } from '@astryxdesign/core/SideNav'
import { Text } from '@astryxdesign/core/Text'
import { TopNav, TopNavHeading } from '@astryxdesign/core/TopNav'
import type { ReactNode } from 'react'
import { Outlet, useLocation, useParams } from 'react-router-dom'
import { DegradedState, EmptyStatePanel, ErrorState, LoadingState } from './dashboardState'

interface NavigationItem {
  readonly href: string
  readonly label: string
}

interface RouteSlotProps {
  readonly children: ReactNode
  readonly description: string
  readonly title: string
}

const navigationItems: readonly NavigationItem[] = [
  { href: '/browse', label: '탐색' },
  { href: '/search', label: '검색' },
  { href: '/trace', label: '추적' },
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
      <header className="route-slot__header">
        <Text as="h1" type="display-2">
          {title}
        </Text>
        <Text as="p" color="secondary" type="large">
          {description}
        </Text>
      </header>
      {children}
    </div>
  )
}

function DashboardNavigation() {
  const location = useLocation()

  return (
    <SideNav
      aria-label="주요 탐색"
      footer={
        <Text as="p" color="secondary" type="supporting">
          서버 데이터를 변경하지 않는 읽기 전용 화면입니다.
        </Text>
      }
      header={
        <SideNavHeading
          heading="Plan2Agent Memory"
          headingHref="/browse"
          icon={<NavIcon icon={<span aria-hidden="true">P</span>} />}
          subheading="읽기 전용 탐색기"
        />
      }
    >
      <SideNavSection title="조회">
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

export function DashboardShell() {
  return (
    <AppShell
      contentPadding={0}
      height="auto"
      mobileNav={{ breakpoint: 'md' }}
      sideNav={<DashboardNavigation />}
      topNav={
        <TopNav
          endContent={
            <Text color="secondary" type="supporting">
              읽기 전용
            </Text>
          }
          heading={<TopNavHeading heading="Plan2Agent Memory" headingHref="/browse" />}
          label="대시보드 탐색"
        />
      }
      variant="elevated"
    >
      <Outlet />
    </AppShell>
  )
}

export function BrowseRouteSlot() {
  return (
    <RouteSlot
      description="프로젝트, 이터레이션, 산출물 계층이 이 영역에 순서대로 표시됩니다."
      title="산출물 탐색"
    >
      <LoadingState
        description="탐색 계층을 표시할 준비를 하고 있습니다. 이후 단계에서 요청과 커서 탐색이 연결됩니다."
        title="프로젝트 목록 준비 중"
      />
    </RouteSlot>
  )
}

export function ArtifactRouteSlot() {
  const { artifactId, artifactType } = useParams()
  const artifactLabel = artifactId && artifactType ? `${artifactType} · ${artifactId}` : '선택한 산출물'

  return (
    <RouteSlot
      description="메타데이터, 안전한 미리보기와 원문 뷰어를 위한 읽기 전용 영역입니다."
      title="산출물 상세"
    >
      <EmptyStatePanel
        description={`${artifactLabel}의 상세 데이터가 연결되면 이 영역에 표시됩니다.`}
        title="표시할 산출물이 없습니다"
      />
    </RouteSlot>
  )
}

export function SearchRouteSlot() {
  return (
    <RouteSlot
      description="키워드, 의미, 혼합 검색 결과가 구분되어 이 영역에 표시됩니다."
      title="검색"
    >
      <EmptyStatePanel
        description="검색어와 검색 방식을 선택하면 읽기 전용 결과 목록이 표시됩니다."
        title="아직 검색 결과가 없습니다"
      />
    </RouteSlot>
  )
}

export function TraceRouteSlot() {
  return (
    <RouteSlot
      description="선택한 산출물의 노드와 간선 관계를 읽기 전용으로 확인하는 영역입니다."
      title="계보 추적"
    >
      <DegradedState
        description="추적 데이터를 사용할 수 없을 때도 탐색과 키워드 검색은 계속 사용할 수 있습니다."
        title="추적 데이터 연결을 준비하고 있습니다"
      />
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
