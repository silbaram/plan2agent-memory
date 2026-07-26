export type ArtifactType =
  | 'PROJECT'
  | 'ITERATION'
  | 'DOCUMENT_SNAPSHOT'
  | 'TASK_GRAPH'
  | 'TASK'
  | 'RUN_RECORD'
  | 'PROPOSAL'
  | 'DOCUMENT_CHUNK'

export type DashboardArtifactType =
  | 'DOCUMENT_SNAPSHOT'
  | 'TASK_GRAPH'
  | 'TASK'
  | 'RUN_RECORD'
  | 'PROPOSAL'

export type GraphNodeKind =
  | 'DECISION'
  | 'ASSUMPTION'
  | 'CLARIFYING_QUESTION'
  | 'EVIDENCE'
  | 'SPEC_SECTION'
  | 'DOCUMENT'
  | 'TASK'
  | 'RUN'
  | 'PROPOSAL'

export type GraphTraceDirection = 'UPSTREAM' | 'DOWNSTREAM' | 'BOTH'

export interface ApiRequestOptions {
  readonly signal?: AbortSignal
}

export interface PaginationRequest {
  readonly cursor?: string
  readonly limit?: number
}

export interface HealthResponse {
  readonly status: string
  readonly timestamp: string
}

export interface Page<T> {
  readonly items: readonly T[]
  readonly nextCursor: string | null
}

export interface SourceReference {
  readonly canonicalServerId: string
  readonly uri: string
  readonly path: string | null
  readonly startLine: number | null
  readonly endLine: number | null
  readonly fragment: string | null
}

export interface SourceIds {
  readonly sourceProjectId: string | null
  readonly sourceIterationId: string | null
  readonly sourceDocumentId: string | null
  readonly sourceTaskGraphId: string | null
  readonly sourceTaskId: string | null
  readonly sourceRunId: string | null
  readonly sourceChunkId: string | null
}

export interface ProjectSummary {
  readonly projectId: string
  readonly sourceProjectId: string
  readonly name: string
  readonly canonicalServerId: string
  readonly rootPath: string
  readonly sourceReference: SourceReference | null
  readonly createdAt: string
  readonly updatedAt: string | null
  readonly metadata: Readonly<Record<string, string>>
}

export interface IterationSummary {
  readonly iterationId: string
  readonly projectId: string
  readonly sourceIterationId: string
  readonly label: string
  readonly status: string
  readonly sourceReference: SourceReference | null
  readonly createdAt: string
  readonly updatedAt: string | null
  readonly metadata: Readonly<Record<string, string>>
}

export interface ArtifactLineage {
  readonly projectId: string
  readonly iterationId: string | null
  readonly documentId: string | null
  readonly taskGraphId: string | null
  readonly taskId: string | null
  readonly runId: string | null
  readonly contentHash: string | null
  readonly snapshotVersion: number | null
  readonly artifactRefs: readonly ArtifactReference[]
}

export interface ArtifactReference {
  readonly artifactType: string
  readonly artifactId: string
  readonly sourcePath: string | null
}

export interface ArtifactSource {
  readonly sourceProjectId: string | null
  readonly sourceIterationId: string | null
  readonly sourceDocumentId: string | null
  readonly sourceTaskGraphId: string | null
  readonly sourceTaskId: string | null
  readonly sourceRunId: string | null
  readonly sourcePath: string | null
  readonly sourceReference: SourceReference | null
}

export interface ArtifactDetail {
  readonly artifactType: DashboardArtifactType
  readonly artifactId: string
  readonly projectId: string
  readonly iterationId: string | null
  readonly title: string
  readonly source: ArtifactSource
  readonly lineage: ArtifactLineage
  readonly mediaType: string
  readonly rawContent: string
  readonly createdAt: string | null
  readonly updatedAt: string | null
  readonly metadata: Readonly<Record<string, string>>
}

export interface ArtifactLookupItem {
  readonly artifactType: string
  readonly artifactId: string
  readonly projectId: string
  readonly iterationId: string | null
  readonly taskId: string | null
  readonly runId: string | null
  readonly sourcePath: string | null
  readonly title: string
  readonly contentHash: string | null
  readonly snapshotVersion: number | null
  readonly lineage: ArtifactLookupLineage
  readonly sourceIds: SourceIds
  readonly sourceReference: SourceReference | null
  readonly createdAt: string | null
  readonly updatedAt: string | null
  readonly metadata: Readonly<Record<string, string>>
}

export interface ArtifactLookupLineage {
  readonly projectId: string
  readonly iterationId: string | null
  readonly sourcePath: string | null
  readonly contentHash: string | null
  readonly snapshotVersion: number | null
  readonly taskId: string | null
  readonly runId: string | null
}

export interface SearchLineage {
  readonly projectId: string
  readonly iterationId: string | null
  readonly documentId: string | null
  readonly chunkId: string | null
  readonly sourcePath: string | null
  readonly chunkIndex: number | null
}

export interface SearchCitation {
  readonly lineage: SearchLineage
  readonly sourceIds: SourceIds
  readonly sourceReference: SourceReference | null
}

export interface SearchItemBase {
  readonly chunkId: string | null
  readonly documentId: string | null
  readonly projectId: string
  readonly iterationId: string | null
  readonly artifactType: string
  readonly sourcePath: string | null
  readonly chunkIndex: number | null
  readonly content: string
  readonly score: number
  readonly lineage: SearchLineage
  readonly sourceIds: SourceIds
  readonly sourceReference: SourceReference | null
  readonly citation: SearchCitation
  readonly metadata: Readonly<Record<string, string>>
}

export interface KeywordSearchItem extends SearchItemBase {
  readonly matchReason: string
}

export interface SemanticSearchItem extends SearchItemBase {
  readonly distanceMetric: string
  readonly embeddingModel: string
  readonly embeddingVersion: string
}

export interface HybridSearchArm {
  readonly rank: number
  readonly score: number
}

export interface HybridSearchItem extends SearchItemBase {
  readonly matchReason: string
  readonly keyword: HybridSearchArm | null
  readonly vector: HybridSearchArm | null
}

export interface GraphNode {
  readonly nodeId: string
  readonly projectId: string
  readonly iterationId: string | null
  readonly nodeKind: string
  readonly naturalKey: string
  readonly label: string
  readonly content: string | null
  readonly documentId: string | null
  readonly taskId: string | null
  readonly runId: string | null
  readonly metadata: Readonly<Record<string, string>>
}

export interface GraphTraceNode {
  readonly node: GraphNode
  readonly depth: number
}

export interface GraphEdge {
  readonly edgeId: string
  readonly projectId: string
  readonly fromNodeId: string
  readonly toNodeId: string
  readonly edgeType: string
  readonly sourceReference: string | null
  readonly metadata: Readonly<Record<string, string>>
}

export interface GraphTrace {
  readonly root: GraphNode
  readonly nodes: readonly GraphTraceNode[]
  readonly edges: readonly GraphEdge[]
  readonly truncated: boolean
}

export type ProjectListRequest = PaginationRequest

export interface IterationListRequest extends PaginationRequest {
  readonly projectId: string
}

export interface ArtifactListRequest extends PaginationRequest {
  readonly projectId?: string
  readonly iterationId?: string
  readonly sourceProjectId?: string
  readonly sourceIterationId?: string
  readonly sourceDocumentId?: string
  readonly sourceTaskGraphId?: string
  readonly sourceTaskId?: string
  readonly sourceRunId?: string
  readonly artifactType?: ArtifactType
  readonly artifactTypes?: readonly DashboardArtifactType[]
  readonly sourcePath?: string
  readonly taskId?: string
  readonly runId?: string
  readonly contentHash?: string
  readonly sourceReferenceCanonicalServerId?: string
  readonly sourceReferenceUri?: string
}

export interface ArtifactDetailRequest {
  readonly artifactType: DashboardArtifactType
  readonly artifactId: string
}

export interface SearchFilters extends PaginationRequest {
  readonly projectId?: string
  readonly iterationId?: string
  readonly artifactType?: ArtifactType
  readonly sourcePath?: string
  readonly taskId?: string
  readonly runId?: string
}

export interface KeywordSearchRequest extends SearchFilters {
  readonly q: string
}

export interface SemanticSearchRequest extends SearchFilters {
  readonly q: string
  readonly metadataFilters?: Readonly<Record<string, string>>
}

export interface HybridSearchRequest extends SemanticSearchRequest {
  readonly rrfK?: number
  readonly candidateLimit?: number
}

export interface GraphNodeListRequest {
  readonly projectId?: string
  readonly iterationId?: string
  readonly nodeKind?: GraphNodeKind
  readonly query?: string
  readonly limit?: number
}

export interface GraphTraceRequest {
  readonly projectId: string
  readonly naturalKey: string
  readonly iterationId?: string
  readonly direction?: GraphTraceDirection
  readonly maxDepth?: number
}
