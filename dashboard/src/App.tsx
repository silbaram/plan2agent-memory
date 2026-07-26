import { forwardRef, type ComponentPropsWithoutRef } from 'react'
import { LinkProvider } from '@astryxdesign/core/Link'
import { Theme } from '@astryxdesign/core/theme'
import { neutralTheme } from '@astryxdesign/theme-neutral/built'
import { BrowserRouter, Link as RouterLink, Route, Routes } from 'react-router-dom'
import {
  ArtifactRouteSlot,
  BrowseRouteSlot,
  DashboardShell,
  NotFoundRouteSlot,
  SearchRouteSlot,
  TraceRouteSlot,
} from './dashboardShell'
import './appShell.css'

type DashboardRouterLinkProps = Omit<ComponentPropsWithoutRef<typeof RouterLink>, 'to'> & {
  href: string
}

const DashboardRouterLink = forwardRef<HTMLAnchorElement, DashboardRouterLinkProps>(
  function DashboardRouterLink({ href, ...props }, ref) {
    return <RouterLink ref={ref} to={href} {...props} />
  },
)

export function App() {
  return (
    <Theme theme={neutralTheme}>
      <BrowserRouter>
        <LinkProvider component={DashboardRouterLink}>
          <a className="skip-link" href="#main-content">
            본문으로 건너뛰기
          </a>
          <Routes>
            <Route element={<DashboardShell />}>
              <Route path="/" element={<BrowseRouteSlot />} />
              <Route path="/browse" element={<BrowseRouteSlot />} />
              <Route path="/artifact/:artifactType/:artifactId" element={<ArtifactRouteSlot />} />
              <Route path="/search" element={<SearchRouteSlot />} />
              <Route path="/trace" element={<TraceRouteSlot />} />
              <Route path="/trace/:artifactType/:artifactId" element={<TraceRouteSlot />} />
              <Route path="*" element={<NotFoundRouteSlot />} />
            </Route>
          </Routes>
        </LinkProvider>
      </BrowserRouter>
    </Theme>
  )
}
