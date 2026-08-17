export const DEFAULT_MARKDOWN_SOURCE_LIMIT = 100_000
export const DEFAULT_MARKDOWN_OUTLINE_LIMIT = 200
export const DEFAULT_MARKDOWN_SUMMARY_LIMIT = 280

export type MarkdownDerivationStatus = 'complete' | 'partial' | 'unavailable'

export type MarkdownDerivationReason = 'malformed_fence' | 'outline_limit' | 'source_limit'

export interface MarkdownOutlineEntry {
  readonly fragmentId: string
  readonly headingLevel: number
  readonly label: string
}

export interface MarkdownSummary {
  readonly excerpt: string | null
  readonly title: string | null
}

export interface MarkdownDerivation {
  readonly reasons: readonly MarkdownDerivationReason[]
  readonly status: MarkdownDerivationStatus
}

export interface MarkdownViewModel {
  readonly derivation: MarkdownDerivation
  readonly outline: readonly MarkdownOutlineEntry[]
  readonly rawContent: string
  readonly summary: MarkdownSummary | null
}

export interface MarkdownViewModelOptions {
  readonly maximumOutlineEntries?: number
  readonly maximumSourceLength?: number
  readonly maximumSummaryLength?: number
}

interface Fence {
  readonly marker: '`' | '~'
  readonly minimumLength: number
}

interface ParsedMarkdown {
  readonly firstHeading: string | null
  readonly firstLevelOneHeading: string | null
  readonly firstParagraph: string | null
  readonly hasMalformedFence: boolean
  readonly outline: readonly MarkdownOutlineEntry[]
  readonly reachedOutlineLimit: boolean
}

const atxHeadingPattern = /^ {0,3}(#{1,6})(?:[ \t]+(.*)|[ \t]*)$/
const fencePattern = /^ {0,3}(`{3,}|~{3,})[^\n]*$/

/**
 * Builds a small, display-ready description of Markdown without changing the
 * source that the Markdown preview and raw-content tab consume.
 */
export function createMarkdownViewModel(
  rawContent: string,
  options: MarkdownViewModelOptions = {},
): MarkdownViewModel {
  const maximumSourceLength = validLimit(options.maximumSourceLength, DEFAULT_MARKDOWN_SOURCE_LIMIT)
  const maximumOutlineEntries = validLimit(options.maximumOutlineEntries, DEFAULT_MARKDOWN_OUTLINE_LIMIT)
  const maximumSummaryLength = validLimit(options.maximumSummaryLength, DEFAULT_MARKDOWN_SUMMARY_LIMIT)
  const sourceWasLimited = rawContent.length > maximumSourceLength
  const inspectedSource = sourceWasLimited
    ? completeLinesBefore(rawContent, maximumSourceLength)
    : rawContent
  const parsed = parseMarkdown(inspectedSource, maximumOutlineEntries)

  if (!sourceWasLimited && parsed.hasMalformedFence) {
    return {
      derivation: { reasons: ['malformed_fence'], status: 'unavailable' },
      outline: [],
      rawContent,
      summary: null,
    }
  }

  const reasons: MarkdownDerivationReason[] = []
  if (sourceWasLimited) {
    reasons.push('source_limit')
  }
  if (parsed.reachedOutlineLimit) {
    reasons.push('outline_limit')
  }

  return {
    derivation: {
      reasons,
      status: reasons.length === 0 ? 'complete' : 'partial',
    },
    outline: parsed.outline,
    rawContent,
    summary: createSummary(parsed, maximumSummaryLength),
  }
}

function parseMarkdown(source: string, maximumOutlineEntries: number): ParsedMarkdown {
  const outline: MarkdownOutlineEntry[] = []
  const fragmentOccurrences = new Map<string, number>()
  let firstHeading: string | null = null
  let firstLevelOneHeading: string | null = null
  let firstParagraph: string | null = null
  let openFence: Fence | null = null
  let reachedOutlineLimit = false

  for (const line of source.split('\n')) {
    const normalizedLine = line.endsWith('\r') ? line.slice(0, -1) : line
    if (openFence !== null) {
      if (isFenceClosing(normalizedLine, openFence)) {
        openFence = null
      }
      continue
    }

    const openingFence = fenceOpening(normalizedLine)
    if (openingFence !== null) {
      openFence = openingFence
      continue
    }

    const heading = headingFromLine(normalizedLine)
    if (heading !== null) {
      if (firstHeading === null && heading.label.length > 0) {
        firstHeading = heading.label
      }
      if (heading.level === 1 && firstLevelOneHeading === null && heading.label.length > 0) {
        firstLevelOneHeading = heading.label
      }
      if (outline.length < maximumOutlineEntries) {
        outline.push({
          fragmentId: uniqueFragmentId(heading.label, fragmentOccurrences),
          headingLevel: heading.level,
          label: heading.label,
        })
      } else {
        reachedOutlineLimit = true
      }
      continue
    }

    if (firstParagraph === null) {
      const paragraph = plainText(normalizedLine)
      if (paragraph.length > 0 && !isMarkdownStructuralLine(normalizedLine)) {
        firstParagraph = paragraph
      }
    }
  }

  return {
    firstHeading,
    firstLevelOneHeading,
    firstParagraph,
    hasMalformedFence: openFence !== null,
    outline,
    reachedOutlineLimit,
  }
}

function headingFromLine(line: string): { readonly label: string; readonly level: number } | null {
  const match = atxHeadingPattern.exec(line)
  if (match === null) {
    return null
  }

  const marker = match[1]
  const label = plainText((match[2] ?? '').replace(/[ \t]+#+[ \t]*$/, ''))
  return { label, level: marker.length }
}

function fenceOpening(line: string): Fence | null {
  const match = fencePattern.exec(line)
  if (match === null) {
    return null
  }

  const marker = match[1]
  const firstCharacter = marker.charAt(0)
  if (firstCharacter !== '`' && firstCharacter !== '~') {
    return null
  }
  return { marker: firstCharacter, minimumLength: marker.length }
}

function isFenceClosing(line: string, fence: Fence): boolean {
  const marker = escapeRegularExpression(fence.marker)
  const closingPattern = new RegExp(`^ {0,3}${marker}{${fence.minimumLength},}[ \t]*$`)
  return closingPattern.test(line)
}

function uniqueFragmentId(label: string, occurrences: Map<string, number>): string {
  const base = fragmentBase(label)
  const occurrence = (occurrences.get(base) ?? 0) + 1
  occurrences.set(base, occurrence)
  return occurrence === 1 ? base : `${base}-${occurrence}`
}

function fragmentBase(label: string): string {
  const normalized = label
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-+|-+$/g, '')
  return normalized.length === 0 ? 'section' : normalized
}

function plainText(value: string): string {
  return value
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/<[^>]*>/g, '')
    .replace(/[\\`*_~]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

function isMarkdownStructuralLine(line: string): boolean {
  const trimmed = line.trim()
  return trimmed.length === 0
    || /^([-*+] |\d+[.)] |>|---+$|\*\*\*+$|___+$)/.test(trimmed)
}

function createSummary(parsed: ParsedMarkdown, maximumSummaryLength: number): MarkdownSummary | null {
  const title = parsed.firstLevelOneHeading ?? parsed.firstHeading
  const excerpt = parsed.firstParagraph === null
    ? null
    : truncate(parsed.firstParagraph, maximumSummaryLength)
  return title === null && excerpt === null ? null : { excerpt, title }
}

function truncate(value: string, maximumLength: number): string {
  if (value.length <= maximumLength) {
    return value
  }
  return `${value.slice(0, Math.max(0, maximumLength - 1)).trimEnd()}…`
}

function completeLinesBefore(source: string, maximumLength: number): string {
  const lineBoundary = source.lastIndexOf('\n', maximumLength)
  return lineBoundary === -1 ? '' : source.slice(0, lineBoundary + 1)
}

function validLimit(value: number | undefined, fallback: number): number {
  return value !== undefined && Number.isSafeInteger(value) && value > 0 ? value : fallback
}

function escapeRegularExpression(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
