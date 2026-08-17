import { describe, expect, it } from 'vitest'
import {
  createMarkdownViewModel,
  DEFAULT_MARKDOWN_SOURCE_LIMIT,
} from './markdownViewModel'

describe('createMarkdownViewModel', () => {
  it('creates deterministic fragment IDs for duplicate, symbol-only, and empty headings', () => {
    const model = createMarkdownViewModel([
      '# Delivery plan',
      '',
      'A concise overview.',
      '',
      '## Delivery plan',
      '### !!!',
      '####',
    ].join('\n'))

    expect(model.derivation).toEqual({ reasons: [], status: 'complete' })
    expect(model.summary).toEqual({ excerpt: 'A concise overview.', title: 'Delivery plan' })
    expect(model.outline).toEqual([
      { fragmentId: 'delivery-plan', headingLevel: 1, label: 'Delivery plan' },
      { fragmentId: 'delivery-plan-2', headingLevel: 2, label: 'Delivery plan' },
      { fragmentId: 'section', headingLevel: 3, label: '!!!' },
      { fragmentId: 'section-2', headingLevel: 4, label: '' },
    ])
  })

  it('retains Korean letters and normalizes symbol-heavy headings to stable fragments', () => {
    const model = createMarkdownViewModel('# 배포 계획\n## C++ & APIs\n## [문서 링크](https://example.com/docs)')

    expect(model.outline).toEqual([
      { fragmentId: '배포-계획', headingLevel: 1, label: '배포 계획' },
      { fragmentId: 'c-apis', headingLevel: 2, label: 'C++ & APIs' },
      { fragmentId: '문서-링크', headingLevel: 2, label: '문서 링크' },
    ])
  })

  it('does not derive headings or summary text from fenced code', () => {
    const rawContent = [
      '# Visible heading',
      '',
      '```markdown',
      '# Hidden heading',
      'Hidden summary.',
      '```',
      '',
      '## Visible section',
    ].join('\n')

    const model = createMarkdownViewModel(rawContent)

    expect(model.rawContent).toBe(rawContent)
    expect(model.summary).toEqual({ excerpt: null, title: 'Visible heading' })
    expect(model.outline).toEqual([
      { fragmentId: 'visible-heading', headingLevel: 1, label: 'Visible heading' },
      { fragmentId: 'visible-section', headingLevel: 2, label: 'Visible section' },
    ])
  })

  it('returns only partial derivation from completed lines before the source-size limit', () => {
    const rawContent = '# Included\nintro\n## Excluded\n'.padEnd(DEFAULT_MARKDOWN_SOURCE_LIMIT + 1, 'x')
    const model = createMarkdownViewModel(rawContent, { maximumSourceLength: 17 })

    expect(model.rawContent).toBe(rawContent)
    expect(model.derivation).toEqual({ reasons: ['source_limit'], status: 'partial' })
    expect(model.outline).toEqual([{ fragmentId: 'included', headingLevel: 1, label: 'Included' }])
    expect(model.summary).toEqual({ excerpt: 'intro', title: 'Included' })
  })

  it('makes derived data unavailable when a complete source has an unclosed fence', () => {
    const rawContent = '# Before fence\n\n```ts\n# Not a heading\nconst unfinished = true\n'
    const model = createMarkdownViewModel(rawContent)

    expect(model.rawContent).toBe(rawContent)
    expect(model.derivation).toEqual({ reasons: ['malformed_fence'], status: 'unavailable' })
    expect(model.summary).toBeNull()
    expect(model.outline).toEqual([])
  })

  it('returns equal view models across repeated runs with the same source', () => {
    const rawContent = '# Repeatable\n\nSummary line\n\n## Repeatable'

    expect(createMarkdownViewModel(rawContent)).toEqual(createMarkdownViewModel(rawContent))
  })
})
