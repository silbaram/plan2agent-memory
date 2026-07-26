import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { App } from './App'

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
    expect(screen.getByRole('status', { name: '프로젝트 목록 준비 중' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '본문으로 건너뛰기' }).getAttribute('href')).toBe('#main-content')
  })

  it('keeps route navigation keyboard focusable and opens the search slot', () => {
    render(<App />)

    const mainNavigation = screen.getAllByRole('navigation', { name: '주요 탐색' })[0]
    const searchLink = within(mainNavigation).getByRole('link', { name: '검색' })
    searchLink.focus()

    expect(document.activeElement).toBe(searchLink)

    fireEvent.click(searchLink)

    expect(screen.getByRole('heading', { name: '검색' })).toBeTruthy()
    expect(screen.getByRole('status').textContent).toContain('아직 검색 결과가 없습니다')
  })

  it('provides artifact, degraded trace, and error state route slots', () => {
    setLocation('/artifact/DOCUMENT/example-artifact')
    const artifactRoute = render(<App />)

    expect(screen.getByRole('heading', { name: '산출물 상세' })).toBeTruthy()
    expect(screen.getByText('DOCUMENT · example-artifact의 상세 데이터가 연결되면 이 영역에 표시됩니다.')).toBeTruthy()

    artifactRoute.unmount()
    setLocation('/trace/DOCUMENT/example-artifact')
    const traceRoute = render(<App />)

    expect(screen.getByRole('heading', { name: '계보 추적' })).toBeTruthy()
    expect(screen.getByRole('alert').textContent).toContain('추적 데이터 연결을 준비하고 있습니다')

    traceRoute.unmount()
    setLocation('/not-a-dashboard-route')
    render(<App />)

    expect(screen.getByRole('heading', { name: '화면을 찾을 수 없습니다' })).toBeTruthy()
    expect(screen.getByRole('alert').textContent).toContain('잘못된 대시보드 경로')
  })
})
