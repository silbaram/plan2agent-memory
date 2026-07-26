import { expect, test } from '@playwright/test'
import {
  artifactId,
  mockDashboardApi,
  projectId,
  type DashboardApiRequest,
} from './dashboardFixtures'

const dashboardCsp = [
  "default-src 'self'",
  "base-uri 'none'",
  "object-src 'none'",
  "frame-ancestors 'none'",
  "form-action 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "style-src-attr 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'self'",
  "manifest-src 'self'",
  "worker-src 'self'",
].join('; ')

test('production Fastify dashboard supports browse, detail, search, navigation, and trace without console errors', async ({ page }) => {
  const requests: DashboardApiRequest[] = []
  const consoleErrors: string[] = []
  await mockDashboardApi(page, requests)
  page.on('console', (message) => {
    if (message.type() === 'error') {
      consoleErrors.push(message.text())
    }
  })

  const browseResponse = await page.goto('/browse')
  expect(browseResponse?.headers()['content-security-policy']).toBe(dashboardCsp)
  await expect(page.getByRole('treeitem', { name: '프로젝트 예제 프로젝트' })).toBeVisible()

  await page.getByRole('treeitem', { name: '프로젝트 예제 프로젝트' }).click()
  await expect(page.getByRole('treeitem', { name: '이터레이션 대시보드 검증' })).toBeVisible()
  await page.getByRole('treeitem', { name: '이터레이션 대시보드 검증' }).click()
  await expect(page.getByRole('treeitem', { name: '작업 프로덕션 대시보드 검증' })).toBeVisible()
  await page.getByRole('treeitem', { name: '작업 프로덕션 대시보드 검증' }).click()
  await expect(page).toHaveURL(new RegExp(`selectedArtifactId=${artifactId}`))

  await page.getByRole('link', { exact: true, name: '검색' }).first().click()
  await page.getByLabel('검색어').fill('프로덕션 검증')
  await page.getByRole('button', { name: '검색' }).click()
  await expect(page.getByRole('link', { name: '부모 산출물 열기' })).toBeVisible()
  await page.getByRole('link', { name: '부모 산출물 열기' }).click()
  await expect(page.getByRole('heading', { level: 1, name: '산출물 상세' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: '안전한 미리보기' })).toBeVisible()
  await expect(page.getByRole('link', { name: '공개 문서' })).toHaveAttribute('href', 'https://example.com/docs')
  await expect(page.locator('script')).toHaveCount(1)
  await page.getByRole('tab', { name: '원문' }).click()
  await expect(page.getByLabel('이스케이프된 원문')).toContainText('<script>window.__unsafe = true</script>')

  await page.getByRole('link', { exact: true, name: '추적' }).first().click()
  await page.getByLabel('추적 시작 노드').selectOption('node-task-024')
  await expect(page.getByRole('region', { name: '계보 결과' })).toBeVisible()
  await expect(page.getByRole('region', { name: '계보 그래프' })).toBeVisible()
  await page.getByLabel('방향').selectOption('UPSTREAM')
  await page.getByLabel('최대 깊이').selectOption('5')
  await expect(page).toHaveURL(/direction=UPSTREAM/)
  await expect(page).toHaveURL(/maxDepth=5/)
  await expect(page.locator('.react-flow__node').first()).toBeVisible()

  expect(consoleErrors).toEqual([])
  expect(requests).not.toHaveLength(0)
  expect(requests.every((request) => request.method === 'GET')).toBe(true)
  expect(requests.every((request) => request.headers['x-p2a-local-token'] === undefined)).toBe(true)
  expect(requests.every((request) => !request.path.includes('playwright-server-only-token'))).toBe(true)
  expect(await page.content()).not.toContain('playwright-server-only-token')
  expect(requests.some((request) => request.path.startsWith(`/api/projects/${projectId}/iterations`))).toBe(true)
})

test('production CSP blocks inline elements and external resources while allowing React Flow style attributes', async ({ page }) => {
  await mockDashboardApi(page)
  const response = await page.goto('/trace')
  expect(response?.headers()['content-security-policy']).toBe(dashboardCsp)

  await page.getByLabel('추적 시작 노드').selectOption('node-task-024')
  const flowNode = page.locator('.react-flow__node').first()
  await expect(flowNode).toBeVisible()
  await expect(flowNode).toHaveAttribute('style', /transform/)

  const result = await page.evaluate(async () => {
    const violations: string[] = []
    document.addEventListener('securitypolicyviolation', (event) => {
      violations.push(event.effectiveDirective)
    })

    const target = document.createElement('div')
    target.id = 'csp-style-target'
    document.body.append(target)

    const inlineScript = document.createElement('script')
    inlineScript.textContent = 'window.__p2aCspScriptExecuted = true'
    document.head.append(inlineScript)

    const inlineStyle = document.createElement('style')
    inlineStyle.textContent = '#csp-style-target { color: rgb(1, 2, 3); }'
    document.head.append(inlineStyle)

    const imageBlocked = await new Promise<boolean>((resolve) => {
      const image = document.createElement('img')
      image.addEventListener('error', () => resolve(true), { once: true })
      image.addEventListener('load', () => resolve(false), { once: true })
      image.src = 'https://csp-fixture.invalid/blocked-image.png'
      document.body.append(image)
    })

    return {
      imageBlocked,
      inlineScriptExecuted: '__p2aCspScriptExecuted' in window,
      targetColor: getComputedStyle(target).color,
      violations,
    }
  })

  expect(result.inlineScriptExecuted).toBe(false)
  expect(result.targetColor).not.toBe('rgb(1, 2, 3)')
  expect(result.imageBlocked).toBe(true)
  expect(result.violations).toEqual(expect.arrayContaining([
    'img-src',
    'script-src-elem',
    'style-src-elem',
  ]))
})
