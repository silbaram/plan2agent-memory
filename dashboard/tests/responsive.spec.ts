import { expect, test, type Page } from '@playwright/test'
import { mockDashboardApi } from './dashboardFixtures'

interface ViewportCase {
  readonly height: number
  readonly name: string
  readonly width: number
}

const stackedViewports: readonly ViewportCase[] = [
  { height: 1024, name: '768×1024', width: 768 },
  { height: 844, name: '390×844', width: 390 },
]

async function openBrowseWorkbench(page: Page, viewport: ViewportCase) {
  await page.setViewportSize(viewport)
  await mockDashboardApi(page)
  await page.goto('/browse')
  await expect(page.getByTestId('dashboard-workbench')).toBeVisible()
}

async function expectNoPageOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
}

test('1440×900 keeps the library, reader, and context as three adjacent panes', async ({ page }) => {
  await openBrowseWorkbench(page, { height: 900, name: '1440×900', width: 1440 })

  const library = page.getByTestId('workbench-library')
  const content = page.getByTestId('workbench-content')
  const context = page.getByTestId('workbench-context')
  const [libraryBox, contentBox, contextBox] = await Promise.all([
    library.boundingBox(),
    content.boundingBox(),
    context.boundingBox(),
  ])

  expect(libraryBox).not.toBeNull()
  expect(contentBox).not.toBeNull()
  expect(contextBox).not.toBeNull()
  expect(libraryBox?.x).toBeLessThan(contentBox?.x ?? 0)
  expect(contentBox?.x).toBeLessThan(contextBox?.x ?? 0)
  await expect(context).toHaveAttribute('data-context-layout', 'panel')
  await expectNoPageOverflow(page)
})

test('1024×768 keeps the reader first and reveals context without changing the URL', async ({ page }) => {
  await openBrowseWorkbench(page, { height: 768, name: '1024×768', width: 1024 })

  const content = page.getByTestId('workbench-content')
  const context = page.getByTestId('workbench-context')
  const [contentBox, contextBox] = await Promise.all([content.boundingBox(), context.boundingBox()])
  const urlBeforeDisclosure = page.url()

  expect(contentBox).not.toBeNull()
  expect(contextBox).not.toBeNull()
  expect(contextBox?.y).toBeGreaterThan(contentBox?.y ?? 0)
  await expect(context).toHaveAttribute('data-context-layout', 'disclosure')
  await expect(page.getByRole('button', { name: '맥락 펼치기' })).toHaveAttribute('aria-expanded', 'false')
  await expect(page.locator('#workbench-context-content')).toBeHidden()

  await page.getByRole('button', { name: '맥락 펼치기' }).click()

  await expect(page.getByRole('button', { name: '맥락 접기' })).toHaveAttribute('aria-expanded', 'true')
  await expect(page.locator('#workbench-context-content')).toBeVisible()
  expect(page.url()).toBe(urlBeforeDisclosure)
  await expectNoPageOverflow(page)
})

for (const viewport of stackedViewports) {
  test(`${viewport.name} stacks library, reader, then context without page overflow`, async ({ page }) => {
    await openBrowseWorkbench(page, viewport)

    const [libraryBox, contentBox, contextBox] = await Promise.all([
      page.getByTestId('workbench-library').boundingBox(),
      page.getByTestId('workbench-content').boundingBox(),
      page.getByTestId('workbench-context').boundingBox(),
    ])

    expect(libraryBox).not.toBeNull()
    expect(contentBox).not.toBeNull()
    expect(contextBox).not.toBeNull()
    expect(Math.abs((libraryBox?.x ?? 0) - (contentBox?.x ?? 0))).toBeLessThanOrEqual(1)
    expect(Math.abs((contentBox?.x ?? 0) - (contextBox?.x ?? 0))).toBeLessThanOrEqual(1)
    expect(contentBox?.y).toBeGreaterThan(libraryBox?.y ?? 0)
    expect(contextBox?.y).toBeGreaterThan(contentBox?.y ?? 0)
    await expect(page.getByTestId('workbench-context')).toHaveAttribute('data-context-layout', 'disclosure')
    await expectNoPageOverflow(page)
  })
}
