import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

test('dashboard smoke page has no automatically detectable axe violations', async ({ page }) => {
  await page.goto('/')

  const accessibilityScanResults = await new AxeBuilder({ page }).analyze()

  expect(accessibilityScanResults.violations).toEqual([])
})
