import { useEffect, useState } from 'react'
import {
  dashboardApi,
  isDashboardApiError,
  type ArtifactDetail,
  type ArtifactLookupItem,
  type DashboardApiClient,
  type SourceReference,
} from '../data-access'
import { ArtifactContent } from './ArtifactContent'
import { isMarkdownMediaType } from './artifactContentPresentation'
import { isSupportedArtifactType } from './artifactTypes'
import { deriveLineageWork, type LineageWorkDerivation } from './gateLineagePresentation'
import { createMarkdownViewModel, type MarkdownViewModel } from './markdownViewModel'
import { summarizeTaskGraphContent, type TaskGraphDerivedInfo } from './taskGraphPresentation'
import './artifactViewer.css'

export interface ArtifactViewerProps {
  readonly artifactId: string
  readonly artifactType: string
  readonly client?: Pick<DashboardApiClient, 'getArtifact'>
  readonly onArtifactLoad?: (artifact: ArtifactDetail | null) => void
  readonly workArtifacts?: readonly ArtifactLookupItem[]
}

interface MetadataRow {
  readonly label: string
  readonly value: number | string | null
}

interface ArtifactViewerError {
  readonly description: string
  readonly title: string
}

type ArtifactRequestState =
  | { readonly requestKey: string; readonly status: 'loading' }
  | { readonly error: unknown; readonly requestKey: string; readonly status: 'error' }
  | { readonly artifact: ArtifactDetail; readonly requestKey: string; readonly status: 'success' }

function buildRequestKey(artifactType: string, artifactId: string) {
  return `${artifactType}\u0000${artifactId}`
}

function hasAbortName(error: unknown) {
  return typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError'
}

function errorForRequest(error: unknown): ArtifactViewerError {
  if (isDashboardApiError(error)) {
    switch (error.code) {
      case 'not_found':
        return {
          description: '요청한 유형과 식별자에 해당하는 산출물을 찾을 수 없습니다.',
          title: '산출물을 찾을 수 없습니다',
        }
      case 'invalid_request':
        return {
          description: '산출물 유형과 식별자가 서버 요구사항에 맞는지 확인하세요.',
          title: '유효하지 않은 산출물 요청',
        }
      case 'bff_response_too_large':
        return {
          description: '대시보드는 최대 10 MiB 응답만 표시할 수 있습니다. 더 작은 산출물을 선택하세요.',
          title: '산출물 응답이 너무 큽니다',
        }
      default:
        return {
          description: '산출물 데이터를 가져오지 못했습니다. 잠시 후 다시 시도하세요.',
          title: '산출물을 표시할 수 없습니다',
        }
    }
  }

  return {
    description: '산출물 데이터를 가져오지 못했습니다. 잠시 후 다시 시도하세요.',
    title: '산출물을 표시할 수 없습니다',
  }
}

function MetadataList({ rows }: { readonly rows: readonly MetadataRow[] }) {
  return (
    <dl className="artifact-viewer__metadata-list">
      {rows.map((row) => (
        <div key={row.label}>
          <dt>{row.label}</dt>
          <dd>{row.value ?? '—'}</dd>
        </div>
      ))}
    </dl>
  )
}

function SourceReferenceMetadata({ sourceReference }: { readonly sourceReference: SourceReference | null }) {
  if (sourceReference === null) {
    return <p className="artifact-viewer__empty">저장된 원본 참조가 없습니다.</p>
  }

  return (
    <MetadataList
      rows={[
        { label: 'Canonical server ID', value: sourceReference.canonicalServerId },
        { label: 'URI', value: sourceReference.uri },
        { label: 'Path', value: sourceReference.path },
        { label: 'Start line', value: sourceReference.startLine },
        { label: 'End line', value: sourceReference.endLine },
        { label: 'Fragment', value: sourceReference.fragment },
      ]}
    />
  )
}

function StoredMetadata({ metadata }: { readonly metadata: Readonly<Record<string, string>> }) {
  const entries = Object.entries(metadata).sort(([left], [right]) => left.localeCompare(right))
  if (entries.length === 0) {
    return <p className="artifact-viewer__empty">저장된 추가 메타데이터가 없습니다.</p>
  }

  return (
    <dl className="artifact-viewer__metadata-list">
      {entries.map(([key, value]) => (
        <div key={key}>
          <dt>{key}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  )
}

function ArtifactMetadata({ artifact }: { readonly artifact: ArtifactDetail }) {
  return (
    <section aria-labelledby="artifact-viewer-artifact-metadata" className="artifact-viewer__metadata-section">
      <h4 id="artifact-viewer-artifact-metadata">산출물 메타데이터</h4>
      <MetadataList
        rows={[
          { label: 'Type', value: artifact.artifactType },
          { label: 'Artifact ID', value: artifact.artifactId },
          { label: 'Project ID', value: artifact.projectId },
          { label: 'Iteration ID', value: artifact.iterationId },
          { label: 'Media type', value: artifact.mediaType },
          { label: 'Created at', value: artifact.createdAt },
          { label: 'Updated at', value: artifact.updatedAt },
        ]}
      />
      <h4>추가 메타데이터</h4>
      <StoredMetadata metadata={artifact.metadata} />
    </section>
  )
}

function SourceMetadata({ artifact }: { readonly artifact: ArtifactDetail }) {
  return (
    <section aria-labelledby="artifact-viewer-source-metadata" className="artifact-viewer__metadata-section">
      <h4 id="artifact-viewer-source-metadata">원본 메타데이터</h4>
      <MetadataList
        rows={[
          { label: 'Source project ID', value: artifact.source.sourceProjectId },
          { label: 'Source iteration ID', value: artifact.source.sourceIterationId },
          { label: 'Source document ID', value: artifact.source.sourceDocumentId },
          { label: 'Source task graph ID', value: artifact.source.sourceTaskGraphId },
          { label: 'Source task ID', value: artifact.source.sourceTaskId },
          { label: 'Source run ID', value: artifact.source.sourceRunId },
          { label: 'Source path', value: artifact.source.sourcePath },
        ]}
      />
      <h4>원본 참조</h4>
      <SourceReferenceMetadata sourceReference={artifact.source.sourceReference} />
    </section>
  )
}

function LineageMetadata({ artifact }: { readonly artifact: ArtifactDetail }) {
  const { lineage } = artifact
  return (
    <section aria-labelledby="artifact-viewer-lineage-metadata" className="artifact-viewer__metadata-section">
      <h4 id="artifact-viewer-lineage-metadata">계보 메타데이터</h4>
      <MetadataList
        rows={[
          { label: 'Project ID', value: lineage.projectId },
          { label: 'Iteration ID', value: lineage.iterationId },
          { label: 'Document ID', value: lineage.documentId },
          { label: 'Task graph ID', value: lineage.taskGraphId },
          { label: 'Task ID', value: lineage.taskId },
          { label: 'Run ID', value: lineage.runId },
          { label: 'Content hash', value: lineage.contentHash },
          { label: 'Snapshot version', value: lineage.snapshotVersion },
        ]}
      />
      <h4>연결된 산출물</h4>
      {lineage.artifactRefs.length === 0 ? (
        <p className="artifact-viewer__empty">저장된 연결 산출물이 없습니다.</p>
      ) : (
        <ul className="artifact-viewer__references">
          {lineage.artifactRefs.map((reference, index) => (
            <li key={`${reference.artifactType}:${reference.artifactId}:${reference.sourcePath ?? ''}:${index}`}>
              <strong>{reference.artifactType}</strong>
              <span>{reference.artifactId}</span>
              {reference.sourcePath === null ? null : <span>{reference.sourcePath}</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function ReaderMetadata({ artifact }: { readonly artifact: ArtifactDetail }) {
  return (
    <section aria-labelledby="artifact-viewer-metadata" className="artifact-viewer__section">
      <h3 id="artifact-viewer-metadata">메타데이터</h3>
      <div className="artifact-viewer__metadata-sections">
        <ArtifactMetadata artifact={artifact} />
        <SourceMetadata artifact={artifact} />
        <LineageMetadata artifact={artifact} />
      </div>
    </section>
  )
}

interface ReaderSummaryProps {
  readonly artifact: ArtifactDetail
  readonly markdownViewModel: MarkdownViewModel | null
  readonly taskGraphInfo: TaskGraphDerivedInfo | null
}

function ReaderSummary({ artifact, markdownViewModel, taskGraphInfo }: ReaderSummaryProps) {
  return (
    <section aria-labelledby="artifact-viewer-summary" className="artifact-viewer__section">
      <h3 id="artifact-viewer-summary">요약</h3>
      {markdownViewModel !== null ? <MarkdownSummary model={markdownViewModel} /> : null}
      {taskGraphInfo !== null ? <TaskGraphSummary info={taskGraphInfo} /> : null}
      {markdownViewModel === null && taskGraphInfo === null ? (
        <p className="artifact-viewer__empty" role="status">
          {artifact.mediaType} 원문에서는 파생 요약을 제공하지 않습니다. 원문 내용은 그대로 유지됩니다.
        </p>
      ) : null}
    </section>
  )
}

function MarkdownSummary({ model }: { readonly model: MarkdownViewModel }) {
  if (model.derivation.status === 'unavailable') {
    return (
      <p className="artifact-viewer__empty" role="status">
        Markdown 요약을 만들 수 없습니다 ({model.derivation.reasons.join(', ')}). 원문은 내용 영역에서 확인할 수 있습니다.
      </p>
    )
  }

  if (model.summary === null) {
    return <p className="artifact-viewer__empty" role="status">표시할 Markdown 요약이 없습니다. 원문은 내용 영역에서 확인할 수 있습니다.</p>
  }

  return (
    <>
      {model.derivation.status === 'partial' ? (
        <p className="artifact-viewer__notice" role="status">
          일부 원문만 바탕으로 요약했습니다 ({model.derivation.reasons.join(', ')}).
        </p>
      ) : null}
      <dl className="artifact-viewer__summary-list">
        <div>
          <dt>문서 제목</dt>
          <dd>{model.summary.title ?? '—'}</dd>
        </div>
        <div>
          <dt>요약</dt>
          <dd>{model.summary.excerpt ?? '—'}</dd>
        </div>
      </dl>
    </>
  )
}

function TaskGraphSummary({ info }: { readonly info: TaskGraphDerivedInfo }) {
  if (info.availability === 'unavailable') {
    return (
      <p className="artifact-viewer__empty" role="status">
        작업 그래프 요약을 만들 수 없습니다 ({info.reason}). 원문은 내용 영역에서 확인할 수 있습니다.
      </p>
    )
  }

  const statusCounts = info.summary.statusCounts.length === 0
    ? '저장된 작업 상태가 없습니다.'
    : info.summary.statusCounts.map(({ count, status }) => `${status}: ${count}`).join(', ')

  return (
    <dl className="artifact-viewer__summary-list">
      <div>
        <dt>작업 수</dt>
        <dd>{info.summary.taskCount}</dd>
      </div>
      <div>
        <dt>의존성 수</dt>
        <dd>{info.summary.dependencyCount}</dd>
      </div>
      <div>
        <dt>상태</dt>
        <dd>{statusCounts}</dd>
      </div>
      <div>
        <dt>현재 작업</dt>
        <dd>{info.summary.currentTaskId ?? '—'}</dd>
      </div>
    </dl>
  )
}

function DocumentOutline({ model }: { readonly model: MarkdownViewModel }) {
  return (
    <section aria-labelledby="artifact-viewer-outline" className="artifact-viewer__section">
      <h3 id="artifact-viewer-outline">목차</h3>
      {model.derivation.status === 'unavailable' ? (
        <p className="artifact-viewer__empty" role="status">
          목차를 만들 수 없습니다 ({model.derivation.reasons.join(', ')}). 원문은 내용 영역에서 확인할 수 있습니다.
        </p>
      ) : model.outline.length === 0 ? (
        <p className="artifact-viewer__empty" role="status">표시할 Markdown heading이 없습니다.</p>
      ) : (
        <nav aria-label="문서 목차">
          <ol className="artifact-viewer__outline-list">
            {model.outline.map((entry) => (
              <li key={entry.fragmentId}>
                <a href={`#${entry.fragmentId}`}>{entry.label || '제목 없음'}</a>
              </li>
            ))}
          </ol>
        </nav>
      )}
      {model.derivation.status === 'partial' ? (
        <p className="artifact-viewer__notice" role="status">
          일부 원문에서 만든 목차입니다 ({model.derivation.reasons.join(', ')}).
        </p>
      ) : null}
    </section>
  )
}

function LinkedWork({ lineageWork }: { readonly lineageWork: LineageWorkDerivation }) {
  return (
    <section aria-labelledby="artifact-viewer-linked-work" className="artifact-viewer__section">
      <h3 id="artifact-viewer-linked-work">연결된 작업</h3>
      {lineageWork.availability === 'unavailable' ? (
        <p className="artifact-viewer__empty" role="status">
          확인된 계보만 연결합니다. 연결된 작업을 확인할 수 없습니다 ({lineageWork.reason}).
        </p>
      ) : lineageWork.linkedWork.length === 0 ? (
        <p className="artifact-viewer__empty" role="status">확인된 계보에 연결된 task 또는 run이 없습니다.</p>
      ) : (
        <ul className="artifact-viewer__linked-work-list">
          {lineageWork.linkedWork.map((work) => (
            <li key={`${work.identity.artifactType}:${work.identity.artifactId}`}>
              {work.href === undefined ? <span>{work.label}</span> : <a href={work.href}>{work.label}</a>}
              <span>{work.status}</span>
              <span>{work.relation}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function ViewerErrorState({ description, title }: ArtifactViewerError) {
  return (
    <section className="artifact-viewer__state" role="alert">
      <h2>{title}</h2>
      <p>{description}</p>
    </section>
  )
}

function ViewerLoadingState() {
  return (
    <section aria-busy="true" className="artifact-viewer__state" role="status">
      <h2>산출물을 불러오는 중</h2>
      <p>저장된 메타데이터와 내용을 읽어오고 있습니다.</p>
    </section>
  )
}

function storedArtifactStatus(artifact: ArtifactDetail): string {
  const status = artifact.metadata.status?.trim()
  return status === undefined || status.length === 0 ? 'unknown' : status
}

export function ArtifactViewer({
  artifactId: artifactIdInput,
  artifactType: artifactTypeInput,
  client = dashboardApi,
  onArtifactLoad,
  workArtifacts = [],
}: ArtifactViewerProps) {
  const artifactId = artifactIdInput.trim()
  const artifactType = artifactTypeInput.trim()
  const supportedArtifactType = isSupportedArtifactType(artifactType) ? artifactType : null
  const requestKey = buildRequestKey(artifactType, artifactId)
  const [state, setState] = useState<ArtifactRequestState>({ requestKey: '', status: 'loading' })

  useEffect(() => {
    if (supportedArtifactType === null || artifactId.length === 0) {
      onArtifactLoad?.(null)
      return undefined
    }

    const abortController = new AbortController()
    onArtifactLoad?.(null)
    void client.getArtifact(
      { artifactId, artifactType: supportedArtifactType },
      { signal: abortController.signal },
    ).then(
      (artifact) => {
        if (!abortController.signal.aborted) {
          setState({ artifact, requestKey, status: 'success' })
          onArtifactLoad?.(artifact)
        }
      },
      (error: unknown) => {
        if (!abortController.signal.aborted && !hasAbortName(error)) {
          setState({ error, requestKey, status: 'error' })
        }
      },
    )

    return () => abortController.abort()
  }, [artifactId, client, onArtifactLoad, requestKey, supportedArtifactType])

  if (supportedArtifactType === null) {
    return (
      <ViewerErrorState
        description={`“${artifactType || '빈 값'}”은(는) 이 대시보드에서 지원하지 않는 산출물 유형입니다.`}
        title="지원하지 않는 산출물 유형"
      />
    )
  }
  if (artifactId.length === 0) {
    return (
      <ViewerErrorState
        description="산출물 식별자를 제공한 뒤 다시 시도하세요."
        title="유효하지 않은 산출물 요청"
      />
    )
  }
  if (state.requestKey !== requestKey || state.status === 'loading') {
    return <ViewerLoadingState />
  }
  if (state.status === 'error') {
    return <ViewerErrorState {...errorForRequest(state.error)} />
  }

  const { artifact } = state
  const markdownViewModel = isMarkdownMediaType(artifact.mediaType)
    ? createMarkdownViewModel(artifact.rawContent)
    : null
  const taskGraphInfo = artifact.artifactType === 'TASK_GRAPH'
    ? summarizeTaskGraphContent(artifact.rawContent)
    : null
  const lineageWork = deriveLineageWork({ artifact, workArtifacts })

  return (
    <article aria-labelledby="artifact-viewer-title" className="artifact-viewer">
      <header className="artifact-viewer__header">
        <p className="artifact-viewer__type">{artifact.artifactType}</p>
        <h2 id="artifact-viewer-title">{artifact.title}</h2>
        <p className="artifact-viewer__identifier">{artifact.artifactId}</p>
        <dl className="artifact-viewer__identity-list">
          <div>
            <dt>원본 경로</dt>
            <dd>{artifact.source.sourcePath ?? '—'}</dd>
          </div>
          <div>
            <dt>상태</dt>
            <dd>{storedArtifactStatus(artifact)}</dd>
          </div>
        </dl>
      </header>
      <ReaderSummary artifact={artifact} markdownViewModel={markdownViewModel} taskGraphInfo={taskGraphInfo} />
      {markdownViewModel === null ? null : <DocumentOutline model={markdownViewModel} />}
      <ArtifactContent
        markdownOutline={markdownViewModel?.outline}
        mediaType={artifact.mediaType}
        rawContent={artifact.rawContent}
      />
      <LinkedWork lineageWork={lineageWork} />
      <ReaderMetadata artifact={artifact} />
    </article>
  )
}
