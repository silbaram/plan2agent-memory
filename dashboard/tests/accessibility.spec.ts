import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { mockDashboardApi } from './dashboardFixtures'

test('production Fastify browse page has no automatically detectable axe violations', async ({ page }) => {
  await mockDashboardApi(page)
  await page.goto('/browse')
  await expect(page.getByRole('treeitem', { name: '프로젝트 예제 프로젝트' })).toBeVisible()

  const accessibilityScanResults = await new AxeBuilder({ page }).analyze()

  expect(accessibilityScanResults.violations).toEqual([])
})
