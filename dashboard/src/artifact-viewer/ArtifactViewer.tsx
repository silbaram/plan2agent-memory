import { useEffect, useState } from 'react'
import {
  dashboardApi,
  isDashboardApiError,
  type ArtifactDetail,
  type DashboardApiClient,
  type SourceReference,
} from '../data-access'
import { ArtifactContent } from './ArtifactContent'
import { isSupportedArtifactType } from './artifactTypes'
import './artifactViewer.css'

export interface ArtifactViewerProps {
  readonly artifactId: string
  readonly artifactType: string
  readonly client?: Pick<DashboardApiClient, 'getArtifact'>
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
    <section aria-labelledby="artifact-viewer-artifact-metadata" className="artifact-viewer__section">
      <h3 id="artifact-viewer-artifact-metadata">산출물 메타데이터</h3>
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
    <section aria-labelledby="artifact-viewer-source-metadata" className="artifact-viewer__section">
      <h3 id="artifact-viewer-source-metadata">원본 메타데이터</h3>
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
    <section aria-labelledby="artifact-viewer-lineage-metadata" className="artifact-viewer__section">
      <h3 id="artifact-viewer-lineage-metadata">계보 메타데이터</h3>
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

export function ArtifactViewer({ artifactId: artifactIdInput, artifactType: artifactTypeInput, client = dashboardApi }: ArtifactViewerProps) {
  const artifactId = artifactIdInput.trim()
  const artifactType = artifactTypeInput.trim()
  const supportedArtifactType = isSupportedArtifactType(artifactType) ? artifactType : null
  const requestKey = buildRequestKey(artifactType, artifactId)
  const [state, setState] = useState<ArtifactRequestState>({ requestKey: '', status: 'loading' })

  useEffect(() => {
    if (supportedArtifactType === null || artifactId.length === 0) {
      return undefined
    }

    const abortController = new AbortController()
    void client.getArtifact(
      { artifactId, artifactType: supportedArtifactType },
      { signal: abortController.signal },
    ).then(
      (artifact) => {
        if (!abortController.signal.aborted) {
          setState({ artifact, requestKey, status: 'success' })
        }
      },
      (error: unknown) => {
        if (!abortController.signal.aborted && !hasAbortName(error)) {
          setState({ error, requestKey, status: 'error' })
        }
      },
    )

    return () => abortController.abort()
  }, [artifactId, client, requestKey, supportedArtifactType])

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
  return (
    <article aria-labelledby="artifact-viewer-title" className="artifact-viewer">
      <header className="artifact-viewer__header">
        <p className="artifact-viewer__type">{artifact.artifactType}</p>
        <h2 id="artifact-viewer-title">{artifact.title}</h2>
        <p className="artifact-viewer__identifier">{artifact.artifactId}</p>
      </header>
      <ArtifactMetadata artifact={artifact} />
      <SourceMetadata artifact={artifact} />
      <LineageMetadata artifact={artifact} />
      <ArtifactContent mediaType={artifact.mediaType} rawContent={artifact.rawContent} />
    </article>
  )
}
