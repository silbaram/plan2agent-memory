import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { artifactId, mockDashboardApi } from './dashboardFixtures'

async function expectNoAxeViolations(page: Page) {
  const accessibilityScanResults = await new AxeBuilder({ page }).analyze()

  expect(accessibilityScanResults.violations).toEqual([])
}

async function selectTraceRoot(page: Page) {
  await page.getByLabel('추적 시작 노드').selectOption('node-task-024')
  await expect(page.getByRole('region', { name: '계보 노드와 간선 목록' })).toBeVisible()
}

test('production Fastify browse, viewer, search, and trace states have no automatically detectable axe violations', async ({ page }) => {
  await mockDashboardApi(page)

  await page.goto('/browse')
  await expect(page.getByRole('treeitem', { name: '프로젝트 예제 프로젝트' })).toBeVisible()
  await expectNoAxeViolations(page)

  await page.goto(`/artifact/DOCUMENT_SNAPSHOT/${artifactId}`)
  await expect(page.getByRole('heading', { name: '프로덕션 대시보드 검증' })).toBeVisible()
  await expectNoAxeViolations(page)

  await page.goto('/search?q=프로덕션')
  await expect(page.getByRole('heading', { name: '검색 결과' })).toBeVisible()
  await expectNoAxeViolations(page)

  await page.goto('/trace')
  await selectTraceRoot(page)
  await expectNoAxeViolations(page)
})

test('keyboard-only navigation exposes landmarks, focus, tree selection, tabs, and the compact disclosure', async ({ page }) => {
  await mockDashboardApi(page)
  await page.setViewportSize({ height: 1_000, width: 900 })
  await page.goto('/browse')

  const skipLink = page.getByRole('link', { name: '본문으로 건너뛰기' })
  await skipLink.focus()
  await expect(skipLink).toBeFocused()
  await expect(skipLink).toHaveAttribute('href', '#main-content')
  await expect(page.getByRole('main')).toBeVisible()
  await expect(page.getByRole('navigation', { name: '주요 탐색' })).toBeVisible()

  const project = page.getByRole('treeitem', { name: '프로젝트 예제 프로젝트' })
  await project.focus()
  await expect(project).toBeFocused()
  await expect.poll(async () => project.evaluate((element) => {
    const styles = getComputedStyle(element)
    return styles.outlineStyle !== 'none' || styles.boxShadow !== 'none'
  })).toBe(true)
  await page.keyboard.press('ArrowRight')
  await expect(project).toHaveAttribute('aria-expanded', 'true')
  await page.keyboard.press('ArrowRight')

  const iteration = page.getByRole('treeitem', { name: '이터레이션 대시보드 검증' })
  await expect(iteration).toBeFocused()
  await page.keyboard.press('ArrowRight')
  await expect(iteration).toHaveAttribute('aria-expanded', 'true')
  await page.keyboard.press('ArrowRight')

  const artifact = page.getByRole('treeitem', { name: '작업 프로덕션 대시보드 검증' })
  await expect(artifact).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(artifact).toHaveAttribute('aria-current', 'true')
  await expect(artifact).toHaveAttribute('aria-selected', 'true')

  const disclosure = page.getByRole('button', { name: /맥락 (접기|펼치기)/ })
  await disclosure.focus()
  await page.keyboard.press('Enter')
  await expect(disclosure).toHaveAttribute('aria-expanded', 'true')

  await page.goto(`/artifact/DOCUMENT_SNAPSHOT/${artifactId}`)
  const previewTab = page.getByRole('tab', { name: '미리보기' })
  await previewTab.focus()
  await page.keyboard.press('ArrowRight')
  const rawTab = page.getByRole('tab', { name: '원문' })
  await expect(rawTab).toBeFocused()
  await expect(rawTab).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', await rawTab.getAttribute('id') ?? '')
})

test('invalid search, unavailable trace context, and truncation announce once with programmatic state', async ({ page }) => {
  await mockDashboardApi(page, [], { traceTruncated: true })

  await page.goto('/search')
  const query = page.getByLabel('검색어')
  await query.focus()
  await page.keyboard.press('Enter')
  const validationAlert = page.locator('[role="alert"]').filter({ hasText: '검색어를 입력하세요.' })
  await expect(validationAlert).toHaveCount(1)
  await expect(query).toHaveAttribute('aria-invalid', 'true')
  await expect(query).toHaveAttribute('aria-describedby', await validationAlert.getAttribute('id') ?? '')

  await page.goto('/trace')
  await selectTraceRoot(page)
  const unavailable = page.getByRole('status').filter({ hasText: '명시적으로 완료된 작업 상태가 반환되지 않았습니다.' })
  await expect(unavailable).toHaveCount(1)
  const truncationAlert = page.locator('[role="alert"]').filter({ hasText: '일부 계보만 표시합니다' })
  await expect(truncationAlert).toHaveCount(1)
  await expect(page.getByRole('status').filter({ hasText: '잘린 trace 응답과 동일한 node·edge만 포함합니다.' })).toHaveCount(1)

  const accessibleList = page.getByRole('region', { name: '계보 노드와 간선 목록' })
  await expect(accessibleList).toContainText('노드 ID: node-task-024')
  await expect(accessibleList).toContainText('노드 ID: node-document')
  await expect(accessibleList).toContainText('간선 ID: edge-task-document')
})
