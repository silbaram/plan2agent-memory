import { useId, useState, type ComponentPropsWithoutRef } from 'react'
import ReactMarkdown from 'react-markdown'
import rehypeSanitize from 'rehype-sanitize'
import remarkGfm from 'remark-gfm'

interface ArtifactContentProps {
  readonly mediaType: string
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

function isMarkdownMediaType(mediaType: string) {
  const normalized = normalizeMediaType(mediaType)
  return normalized === 'text/markdown' || normalized === 'text/x-markdown'
}

function isJsonMediaType(mediaType: string) {
  const normalized = normalizeMediaType(mediaType)
  return normalized === 'application/json' || normalized === 'text/json' || normalized.endsWith('+json')
}

function normalizeMediaType(mediaType: string) {
  return mediaType.split(';', 1)[0]?.trim().toLowerCase() ?? ''
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

function MarkdownPreview({ rawContent }: Pick<ArtifactContentProps, 'rawContent'>) {
  return (
    <div aria-label="Markdown 미리보기" className="artifact-viewer__markdown">
      <ReactMarkdown
        components={{ a: SafeMarkdownLink, img: BlockedMarkdownImage }}
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

function Preview({ mediaType, rawContent }: ArtifactContentProps) {
  if (isMarkdownMediaType(mediaType)) {
    return <MarkdownPreview rawContent={rawContent} />
  }
  if (isJsonMediaType(mediaType)) {
    return <JsonPreview rawContent={rawContent} />
  }

  return <pre aria-label="텍스트 미리보기" className="artifact-viewer__content"><code>{rawContent}</code></pre>
}

export function ArtifactContent({ mediaType, rawContent }: ArtifactContentProps) {
  const [activeTab, setActiveTab] = useState<ContentTab>('preview')
  const tabPrefix = useId()
  const previewTabId = `${tabPrefix}-preview-tab`
  const rawTabId = `${tabPrefix}-raw-tab`
  const previewPanelId = `${tabPrefix}-preview-panel`
  const rawPanelId = `${tabPrefix}-raw-panel`

  return (
    <section aria-labelledby={`${tabPrefix}-heading`} className="artifact-viewer__section">
      <h3 id={`${tabPrefix}-heading`}>내용</h3>
      <div aria-label="산출물 내용 보기" className="artifact-viewer__tabs" role="tablist">
        <button
          aria-controls={previewPanelId}
          aria-selected={activeTab === 'preview'}
          id={previewTabId}
          onClick={() => setActiveTab('preview')}
          role="tab"
          type="button"
        >
          미리보기
        </button>
        <button
          aria-controls={rawPanelId}
          aria-selected={activeTab === 'raw'}
          id={rawTabId}
          onClick={() => setActiveTab('raw')}
          role="tab"
          type="button"
        >
          원문
        </button>
      </div>
      {activeTab === 'preview' ? (
        <div aria-labelledby={previewTabId} className="artifact-viewer__tab-panel" id={previewPanelId} role="tabpanel">
          <Preview mediaType={mediaType} rawContent={rawContent} />
        </div>
      ) : (
        <div aria-labelledby={rawTabId} className="artifact-viewer__tab-panel" id={rawPanelId} role="tabpanel">
          <pre aria-label="이스케이프된 원문" className="artifact-viewer__content"><code>{rawContent}</code></pre>
        </div>
      )}
    </section>
  )
}
