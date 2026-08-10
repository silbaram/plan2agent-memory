import { useId, useRef, useState, type ComponentPropsWithoutRef, type KeyboardEvent } from 'react'
import ReactMarkdown, { type Components, type ExtraProps } from 'react-markdown'
import rehypeSanitize from 'rehype-sanitize'
import remarkGfm from 'remark-gfm'
import {
  ARTIFACT_CONTENT_CHUNK_SIZE,
  isJsonMediaType,
  isMarkdownMediaType,
} from './artifactContentPresentation'
import {
  createMarkdownViewModel,
  type MarkdownOutlineEntry,
} from './markdownViewModel'

export interface ArtifactContentProps {
  readonly mediaType: string
  readonly markdownOutline?: readonly MarkdownOutlineEntry[]
  readonly rawContent: string
}

type ContentTab = 'preview' | 'raw'

const markdownSanitizeSchema = {
  attributes: {
    a: ['href', 'title'],
    code: ['className'],
    input: ['checked', 'disabled', 'type'],
    ol: ['start'],
  },
  protocols: {
    href: ['http', 'https'],
  },
  tagNames: [
    'a',
    'blockquote',
    'br',
    'code',
    'del',
    'em',
    'h1',
    'h2',
    'h3',
    'h4',
    'h5',
    'h6',
    'hr',
    'input',
    'li',
    'ol',
    'p',
    'pre',
    'strong',
    'table',
    'tbody',
    'td',
    'th',
    'thead',
    'tr',
    'ul',
  ],
}

function isSafeMarkdownHref(href: string | undefined) {
  if (href === undefined) {
    return false
  }

  const normalized = href.trim()
  if (normalized.length === 0) {
    return false
  }
  if (normalized.startsWith('#')) {
    return true
  }

  try {
    const url = new URL(normalized)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

function SafeMarkdownLink({ children, href, ...props }: ComponentPropsWithoutRef<'a'>) {
  if (!isSafeMarkdownHref(href)) {
    return <span>{children}</span>
  }

  return <a {...props} href={href}>{children}</a>
}

function BlockedMarkdownImage() {
  return null
}

interface MarkdownPreviewProps {
  readonly outline: readonly MarkdownOutlineEntry[]
  readonly rawContent: string
}

function MarkdownPreview({ outline, rawContent }: MarkdownPreviewProps) {
  const components = markdownComponentsFor(rawContent, outline)

  return (
    <div aria-label="Markdown 미리보기" className="artifact-viewer__markdown">
      <ReactMarkdown
        components={components}
        rehypePlugins={[[rehypeSanitize, markdownSanitizeSchema]]}
        remarkPlugins={[remarkGfm]}
        skipHtml
        urlTransform={(url, key) => key === 'src' || !isSafeMarkdownHref(url) ? '' : url}
      >
        {rawContent}
      </ReactMarkdown>
    </div>
  )
}

function formatJson(rawContent: string): string | null {
  try {
    return JSON.stringify(JSON.parse(rawContent), null, 2)
  } catch {
    return null
  }
}

function JsonPreview({ rawContent }: Pick<ArtifactContentProps, 'rawContent'>) {
  const formatted = formatJson(rawContent)
  if (formatted === null) {
    return (
      <p className="artifact-viewer__notice" role="status">
        JSON 미리보기를 표시할 수 없습니다. 원문 탭에서 저장된 내용을 확인하세요.
      </p>
    )
  }

  return <pre aria-label="형식화된 JSON 미리보기" className="artifact-viewer__content"><code>{formatted}</code></pre>
}

function Preview({ markdownOutline, mediaType, rawContent }: ArtifactContentProps) {
  if (isMarkdownMediaType(mediaType)) {
    return <MarkdownPreview outline={markdownOutline ?? createMarkdownViewModel(rawContent).outline} rawContent={rawContent} />
  }
  if (isJsonMediaType(mediaType)) {
    return <JsonPreview rawContent={rawContent} />
  }

  return <pre aria-label="텍스트 미리보기" className="artifact-viewer__content"><code>{rawContent}</code></pre>
}

interface ContentWindow {
  readonly hasMoreContent: boolean
  readonly visibleContent: string
}

interface ContentWindowState {
  readonly rawContent: string
  readonly visibleCharacterCount: number
}

function visibleContentFor(rawContent: string, visibleCharacterCount: number): ContentWindow {
  const visibleContent = rawContent.slice(0, visibleCharacterCount)
  return {
    hasMoreContent: visibleContent.length < rawContent.length,
    visibleContent,
  }
}

function nextVisibleCharacterCount(currentCount: number, contentLength: number): number {
  return Math.min(contentLength, currentCount + ARTIFACT_CONTENT_CHUNK_SIZE)
}

function markdownComponentsFor(rawContent: string, outline: readonly MarkdownOutlineEntry[]): Components {
  const fragmentIdsByLine = markdownFragmentIdsByLine(rawContent, outline)
  const fragmentIdForNode = (node: ExtraProps['node']): string | undefined => {
    const line = node?.position?.start.line
    return line === undefined ? undefined : fragmentIdsByLine.get(line)
  }
  const EmbeddedHeading = ({ node, ...props }: ComponentPropsWithoutRef<'h1'> & ExtraProps) => (
    <h4 {...props} id={fragmentIdForNode(node)} />
  )

  return {
    a: SafeMarkdownLink,
    h1: EmbeddedHeading,
    h2: EmbeddedHeading,
    h3: EmbeddedHeading,
    h4: EmbeddedHeading,
    h5: EmbeddedHeading,
    h6: EmbeddedHeading,
    img: BlockedMarkdownImage,
  }
}

function markdownFragmentIdsByLine(
  rawContent: string,
  outline: readonly MarkdownOutlineEntry[],
): ReadonlyMap<number, string> {
  const fragmentIdsByLine = new Map<number, string>()
  const openingFencePattern = /^ {0,3}(`{3,}|~{3,})[^\n]*$/
  const headingPattern = /^ {0,3}#{1,6}(?:[ \t]+.*|[ \t]*)$/
  let outlineIndex = 0
  let openFence: { readonly marker: '`' | '~'; readonly minimumLength: number } | null = null

  for (const [index, sourceLine] of rawContent.split('\n').entries()) {
    const line = sourceLine.endsWith('\r') ? sourceLine.slice(0, -1) : sourceLine
    if (openFence !== null) {
      const closingMarker = openFence.marker.repeat(openFence.minimumLength)
      const closingPattern = new RegExp(`^ {0,3}${escapeRegularExpression(closingMarker)}[${escapeRegularExpression(openFence.marker)}]*[ \\t]*$`)
      if (closingPattern.test(line)) {
        openFence = null
      }
      continue
    }

    const fenceMatch = openingFencePattern.exec(line)
    if (fenceMatch !== null) {
      const marker = fenceMatch[1]
      const markerCharacter = marker.charAt(0)
      if (markerCharacter === '`' || markerCharacter === '~') {
        openFence = { marker: markerCharacter, minimumLength: marker.length }
      }
      continue
    }

    if (headingPattern.test(line)) {
      const outlineEntry = outline[outlineIndex]
      if (outlineEntry !== undefined) {
        fragmentIdsByLine.set(index + 1, outlineEntry.fragmentId)
      }
      outlineIndex += 1
    }
  }

  return fragmentIdsByLine
}

function escapeRegularExpression(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export function ArtifactContent({ markdownOutline, mediaType, rawContent }: ArtifactContentProps) {
  const [activeTab, setActiveTab] = useState<ContentTab>('preview')
  const [contentWindowState, setContentWindowState] = useState<ContentWindowState>({
    rawContent,
    visibleCharacterCount: ARTIFACT_CONTENT_CHUNK_SIZE,
  })
  const tabPrefix = useId()
  const previewTabId = `${tabPrefix}-preview-tab`
  const rawTabId = `${tabPrefix}-raw-tab`
  const previewPanelId = `${tabPrefix}-preview-panel`
  const rawPanelId = `${tabPrefix}-raw-panel`
  const previewTabRef = useRef<HTMLButtonElement>(null)
  const rawTabRef = useRef<HTMLButtonElement>(null)
  const visibleCharacterCount = contentWindowState.rawContent === rawContent
    ? contentWindowState.visibleCharacterCount
    : ARTIFACT_CONTENT_CHUNK_SIZE
  const { hasMoreContent, visibleContent } = visibleContentFor(rawContent, visibleCharacterCount)

  const selectTab = (tab: ContentTab) => {
    setActiveTab(tab)
  }

  const handleTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const destination = tabForKey(event.key, activeTab)
    if (destination === null) {
      return
    }

    event.preventDefault()
    selectTab(destination)
    const tabRef = destination === 'preview' ? previewTabRef : rawTabRef
    tabRef.current?.focus()
  }

  return (
    <section aria-labelledby={`${tabPrefix}-heading`} className="artifact-viewer__section">
      <h3 id={`${tabPrefix}-heading`}>내용</h3>
      <div aria-label="산출물 내용 보기" className="artifact-viewer__tabs" role="tablist">
        <button
          aria-controls={previewPanelId}
          aria-selected={activeTab === 'preview'}
          id={previewTabId}
          onClick={() => selectTab('preview')}
          onKeyDown={handleTabKeyDown}
          ref={previewTabRef}
          role="tab"
          tabIndex={activeTab === 'preview' ? 0 : -1}
          type="button"
        >
          미리보기
        </button>
        <button
          aria-controls={rawPanelId}
          aria-selected={activeTab === 'raw'}
          id={rawTabId}
          onClick={() => selectTab('raw')}
          onKeyDown={handleTabKeyDown}
          ref={rawTabRef}
          role="tab"
          tabIndex={activeTab === 'raw' ? 0 : -1}
          type="button"
        >
          원문
        </button>
      </div>
      {activeTab === 'preview' ? (
        <div aria-labelledby={previewTabId} className="artifact-viewer__tab-panel" id={previewPanelId} role="tabpanel">
          <Preview markdownOutline={markdownOutline} mediaType={mediaType} rawContent={visibleContent} />
        </div>
      ) : (
        <div aria-labelledby={rawTabId} className="artifact-viewer__tab-panel" id={rawPanelId} role="tabpanel">
          <pre aria-label="이스케이프된 원문" className="artifact-viewer__content"><code>{visibleContent}</code></pre>
        </div>
      )}
      {hasMoreContent ? (
        <div className="artifact-viewer__content-window" role="status">
          <p>원문이 커서 처음 {ARTIFACT_CONTENT_CHUNK_SIZE.toLocaleString()}자만 표시합니다.</p>
          <div>
            <button
              onClick={() => setContentWindowState({
                rawContent,
                visibleCharacterCount: nextVisibleCharacterCount(visibleCharacterCount, rawContent.length),
              })}
              type="button"
            >
              다음 {ARTIFACT_CONTENT_CHUNK_SIZE.toLocaleString()}자 표시
            </button>
            <button
              onClick={() => setContentWindowState({ rawContent, visibleCharacterCount: rawContent.length })}
              type="button"
            >
              전체 내용 표시
            </button>
          </div>
        </div>
      ) : null}
    </section>
  )
}

function tabForKey(key: string, activeTab: ContentTab): ContentTab | null {
  if (key === 'Home') {
    return 'preview'
  }
  if (key === 'End') {
    return 'raw'
  }
  if (key === 'ArrowLeft' || key === 'ArrowRight') {
    return activeTab === 'preview' ? 'raw' : 'preview'
  }
  return null
}
