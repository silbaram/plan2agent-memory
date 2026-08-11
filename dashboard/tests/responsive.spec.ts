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

const artifactTreeViewports: readonly ViewportCase[] = [
  { height: 900, name: '1440×900', width: 1440 },
  { height: 768, name: '1024×768', width: 1024 },
  { height: 1024, name: '768×1024', width: 768 },
  { height: 844, name: '390×844', width: 390 },
]

const thirdLevelArtifactTitles = [
  '프로덕션 대시보드 검증',
  '완료된 검증 실행',
  '실패한 검증 실행',
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

for (const viewport of artifactTreeViewports) {
  test(`${viewport.name} keeps every third-level artifact label readable`, async ({ page }) => {
    await openBrowseWorkbench(page, viewport)

    const project = page.getByRole('treeitem', { name: '프로젝트 예제 프로젝트' })
    await project.click()
    const iteration = page.getByRole('treeitem', { name: '이터레이션 대시보드 검증' })
    await iteration.click()
    const artifact = page.getByRole('treeitem', { name: '작업 프로덕션 대시보드 검증' })
    await artifact.click()

    await expect(artifact).toHaveAttribute('aria-current', 'true')
    await expect(artifact).toHaveAttribute('tabindex', '0')
    await expect(page).toHaveURL(/selectedArtifactId=44444444-4444-4444-8444-444444444444/)

    const leaves = page.locator('[role="treeitem"][aria-level="3"]')
    await expect(leaves).toHaveCount(thirdLevelArtifactTitles.length)
    const layouts = await leaves.evaluateAll((items) => items.map((item) => {
      const itemContent = item.querySelector<HTMLElement>('.artifact-tree__item-content')
      const label = item.querySelector<HTMLElement>('.artifact-tree__label')
      const type = item.querySelector<HTMLElement>('.artifact-tree__type')
      if (itemContent === null || label === null || type === null) {
        throw new Error('Expected every third-level artifact to include type and label content.')
      }

      const labelBox = label.getBoundingClientRect()
      const typeBox = type.getBoundingClientRect()
      const labelStyle = getComputedStyle(label)
      const itemContentStyle = getComputedStyle(itemContent)
      return {
        itemContentDisplay: itemContentStyle.display,
        itemContentGridTemplateColumns: itemContentStyle.gridTemplateColumns,
        labelBoxHeight: labelBox.height,
        labelBoxWidth: labelBox.width,
        labelClientWidth: label.clientWidth,
        labelMinInlineSize: labelStyle.minInlineSize,
        labelScrollWidth: label.scrollWidth,
        title: label.textContent,
        typeBoxHeight: typeBox.height,
        typeBoxWidth: typeBox.width,
        typeScrollWidth: type.scrollWidth,
      }
    }))

    expect(layouts.map((layout) => layout.title)).toEqual(thirdLevelArtifactTitles)
    for (const layout of layouts) {
      expect(layout.itemContentDisplay).toBe('grid')
      expect(layout.itemContentGridTemplateColumns).not.toBe('none')
      expect(layout.labelMinInlineSize).toBe('0px')
      expect(layout.labelBoxWidth).toBeGreaterThan(96)
      expect(layout.labelBoxWidth).toBeGreaterThan(layout.labelBoxHeight)
      expect(layout.labelScrollWidth).toBeLessThanOrEqual(layout.labelClientWidth + 1)
      expect(layout.typeBoxWidth).toBeGreaterThanOrEqual(layout.typeBoxHeight)
      expect(layout.typeScrollWidth).toBeLessThanOrEqual(layout.typeBoxWidth + 1)
    }

    await expectNoPageOverflow(page)
    await expect(page.getByTestId('workbench-library')).toHaveScreenshot(
      `artifact-tree-third-level-labels-${viewport.width}x${viewport.height}.png`,
    )
  })
}
