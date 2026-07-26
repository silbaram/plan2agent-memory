import type {
  ApiRequestOptions,
  ArtifactDetail,
  ArtifactDetailRequest,
  ArtifactLineage,
  ArtifactListRequest,
  ArtifactLookupItem,
  ArtifactLookupLineage,
  ArtifactReference,
  ArtifactSource,
  DashboardArtifactType,
  GraphEdge,
  GraphNode,
  GraphNodeListRequest,
  GraphTrace,
  GraphTraceNode,
  GraphTraceRequest,
  HealthResponse,
  HybridSearchArm,
  HybridSearchItem,
  HybridSearchRequest,
  IterationListRequest,
  IterationSummary,
  KeywordSearchItem,
  KeywordSearchRequest,
  Page,
  PaginationRequest,
  ProjectListRequest,
  ProjectSummary,
  SearchCitation,
  SearchItemBase,
  SearchLineage,
  SemanticSearchItem,
  SemanticSearchRequest,
  SourceIds,
  SourceReference,
} from './types'

export type DashboardApiErrorCode =
  | 'access_denied'
  | 'authentication_required'
  | 'bff_backend_unavailable'
  | 'bff_forbidden_route'
  | 'bff_response_too_large'
  | 'bff_timeout'
  | 'conflict'
  | 'embedding_provider_not_configured'
  | 'embedding_provider_unavailable'
  | 'invalid_request'
  | 'invalid_response'
  | 'network_error'
  | 'not_found'
  | 'request_failed'
  | 'request_too_large'
  | 'service_timeout'
  | 'service_unavailable'

export class DashboardApiError extends Error {
  constructor(
    readonly code: DashboardApiErrorCode,
    readonly status: number | null,
    message: string,
  ) {
    super(message)
    this.name = 'DashboardApiError'
  }
}

export function isDashboardApiError(error: unknown): error is DashboardApiError {
  return error instanceof DashboardApiError
}

export interface DashboardApiClient {
  readonly health: (options?: ApiRequestOptions) => Promise<HealthResponse>
  readonly listProjects: (request?: ProjectListRequest, options?: ApiRequestOptions) => Promise<Page<ProjectSummary>>
  readonly listProjectIterations: (request: IterationListRequest, options?: ApiRequestOptions) => Promise<Page<IterationSummary>>
  readonly listArtifacts: (request?: ArtifactListRequest, options?: ApiRequestOptions) => Promise<Page<ArtifactLookupItem>>
  readonly getArtifact: (request: ArtifactDetailRequest, options?: ApiRequestOptions) => Promise<ArtifactDetail>
  readonly keywordSearch: (request: KeywordSearchRequest, options?: ApiRequestOptions) => Promise<Page<KeywordSearchItem>>
  readonly semanticSearch: (request: SemanticSearchRequest, options?: ApiRequestOptions) => Promise<Page<SemanticSearchItem>>
  readonly hybridSearch: (request: HybridSearchRequest, options?: ApiRequestOptions) => Promise<Page<HybridSearchItem>>
  readonly listGraphNodes: (request?: GraphNodeListRequest, options?: ApiRequestOptions) => Promise<readonly GraphNode[]>
  readonly traceGraph: (request: GraphTraceRequest, options?: ApiRequestOptions) => Promise<GraphTrace>
}

export interface NormalizedPagination {
  readonly cursor: string | null
  readonly limit: number | null
}

export interface NormalizedSearchFilters extends NormalizedPagination {
  readonly projectId: string | null
  readonly iterationId: string | null
  readonly artifactType: string | null
  readonly sourcePath: string | null
  readonly taskId: string | null
  readonly runId: string | null
}

export interface NormalizedKeywordSearchRequest {
  readonly filters: NormalizedSearchFilters
  readonly q: string
}

export interface NormalizedSemanticSearchRequest extends NormalizedKeywordSearchRequest {
  readonly metadataFilters: Readonly<Record<string, string>>
}

export interface NormalizedHybridSearchRequest extends NormalizedSemanticSearchRequest {
  readonly candidateLimit: number | null
  readonly rrfK: number | null
}

export interface NormalizedArtifactListRequest extends NormalizedPagination {
  readonly projectId: string | null
  readonly iterationId: string | null
  readonly sourceProjectId: string | null
  readonly sourceIterationId: string | null
  readonly sourceDocumentId: string | null
  readonly sourceTaskGraphId: string | null
  readonly sourceTaskId: string | null
  readonly sourceRunId: string | null
  readonly artifactType: string | null
  readonly artifactTypes: readonly DashboardArtifactType[]
  readonly sourcePath: string | null
  readonly taskId: string | null
  readonly runId: string | null
  readonly contentHash: string | null
  readonly sourceReferenceCanonicalServerId: string | null
  readonly sourceReferenceUri: string | null
}

export interface NormalizedGraphNodeListRequest {
  readonly projectId: string | null
  readonly iterationId: string | null
  readonly nodeKind: string | null
  readonly query: string | null
  readonly limit: number | null
}

export interface NormalizedGraphTraceRequest {
  readonly projectId: string
  readonly naturalKey: string
  readonly iterationId: string | null
  readonly direction: string | null
  readonly maxDepth: number | null
}

export function normalizePagination(request: PaginationRequest = {}): NormalizedPagination {
  return {
    cursor: normalizeOptionalText(request.cursor),
    limit: request.limit ?? null,
  }
}

export function normalizeKeywordSearchRequest(request: KeywordSearchRequest): NormalizedKeywordSearchRequest {
  return {
    filters: normalizeSearchFilters(request),
    q: requireText(request.q),
  }
}

export function normalizeSemanticSearchRequest(request: SemanticSearchRequest): NormalizedSemanticSearchRequest {
  return {
    ...normalizeKeywordSearchRequest(request),
    metadataFilters: normalizeMetadataFilters(request.metadataFilters),
  }
}

export function normalizeHybridSearchRequest(request: HybridSearchRequest): NormalizedHybridSearchRequest {
  return {
    ...normalizeSemanticSearchRequest(request),
    candidateLimit: request.candidateLimit ?? null,
    rrfK: request.rrfK ?? null,
  }
}

export function normalizeArtifactListRequest(request: ArtifactListRequest = {}): NormalizedArtifactListRequest {
  return {
    ...normalizePagination(request),
    projectId: normalizeOptionalText(request.projectId),
    iterationId: normalizeOptionalText(request.iterationId),
    sourceProjectId: normalizeOptionalText(request.sourceProjectId),
    sourceIterationId: normalizeOptionalText(request.sourceIterationId),
    sourceDocumentId: normalizeOptionalText(request.sourceDocumentId),
    sourceTaskGraphId: normalizeOptionalText(request.sourceTaskGraphId),
    sourceTaskId: normalizeOptionalText(request.sourceTaskId),
    sourceRunId: normalizeOptionalText(request.sourceRunId),
    artifactType: request.artifactType ?? null,
    artifactTypes: normalizeArtifactTypes(request.artifactTypes),
    sourcePath: normalizeOptionalText(request.sourcePath),
    taskId: normalizeOptionalText(request.taskId),
    runId: normalizeOptionalText(request.runId),
    contentHash: normalizeOptionalText(request.contentHash),
    sourceReferenceCanonicalServerId: normalizeOptionalText(request.sourceReferenceCanonicalServerId),
    sourceReferenceUri: normalizeOptionalText(request.sourceReferenceUri),
  }
}

export function normalizeGraphNodeListRequest(request: GraphNodeListRequest = {}): NormalizedGraphNodeListRequest {
  return {
    projectId: normalizeOptionalText(request.projectId),
    iterationId: normalizeOptionalText(request.iterationId),
    nodeKind: request.nodeKind ?? null,
    query: normalizeOptionalText(request.query),
    limit: request.limit ?? null,
  }
}

export function normalizeGraphTraceRequest(request: GraphTraceRequest): NormalizedGraphTraceRequest {
  return {
    projectId: requireText(request.projectId),
    naturalKey: requireText(request.naturalKey),
    iterationId: normalizeOptionalText(request.iterationId),
    direction: request.direction ?? null,
    maxDepth: request.maxDepth ?? null,
  }
}

export function createDashboardApiClient(fetchImpl: typeof fetch = globalThis.fetch): DashboardApiClient {
  return {
    health: (options) => getJson('/api/health', options, parseHealthResponse, fetchImpl),
    listProjects: (request = {}, options) => {
      const pagination = normalizePagination(request)
      return getJson(withQuery('/api/projects', (params) => appendPagination(params, pagination)), options, parseProjectPage, fetchImpl)
    },
    listProjectIterations: (request, options) => {
      const projectId = requireText(request.projectId)
      const pagination = normalizePagination(request)
      return getJson(
        withQuery(`/api/projects/${encodeURIComponent(projectId)}/iterations`, (params) => appendPagination(params, pagination)),
        options,
        parseIterationPage,
        fetchImpl,
      )
    },
    listArtifacts: (request = {}, options) => {
      const normalized = normalizeArtifactListRequest(request)
      return getJson(withQuery('/api/artifacts', (params) => appendArtifactListParameters(params, normalized)), options, parseArtifactLookupPage, fetchImpl)
    },
    getArtifact: (request, options) => getJson(
      `/api/artifacts/${encodeURIComponent(request.artifactType)}/${encodeURIComponent(requireText(request.artifactId))}`,
      options,
      parseArtifactDetail,
      fetchImpl,
    ),
    keywordSearch: (request, options) => {
      const normalized = normalizeKeywordSearchRequest(request)
      return getJson(withQuery('/api/search/keyword', (params) => appendKeywordSearchParameters(params, normalized)), options, parseKeywordSearchPage, fetchImpl)
    },
    semanticSearch: (request, options) => postJson('/api/search/semantic', serializeSemanticSearchRequest(normalizeSemanticSearchRequest(request)), options, parseSemanticSearchPage, fetchImpl),
    hybridSearch: (request, options) => postJson('/api/search/hybrid', serializeHybridSearchRequest(normalizeHybridSearchRequest(request)), options, parseHybridSearchPage, fetchImpl),
    listGraphNodes: (request = {}, options) => {
      const normalized = normalizeGraphNodeListRequest(request)
      return getJson(withQuery('/api/graph/nodes', (params) => appendGraphNodeParameters(params, normalized)), options, parseGraphNodes, fetchImpl)
    },
    traceGraph: (request, options) => {
      const normalized = normalizeGraphTraceRequest(request)
      return getJson(withQuery('/api/graph/trace', (params) => appendGraphTraceParameters(params, normalized)), options, parseGraphTrace, fetchImpl)
    },
  }
}

export const dashboardApi = createDashboardApiClient()

function normalizeSearchFilters(request: KeywordSearchRequest): NormalizedSearchFilters {
  return {
    ...normalizePagination(request),
    projectId: normalizeOptionalText(request.projectId),
    iterationId: normalizeOptionalText(request.iterationId),
    artifactType: request.artifactType ?? null,
    sourcePath: normalizeOptionalText(request.sourcePath),
    taskId: normalizeOptionalText(request.taskId),
    runId: normalizeOptionalText(request.runId),
  }
}

function normalizeOptionalText(value: string | undefined): string | null {
  const normalized = value?.trim()
  return normalized === undefined || normalized.length === 0 ? null : normalized
}

function requireText(value: string): string {
  const normalized = value.trim()
  if (normalized.length === 0) {
    throw publicError('invalid_request', null)
  }
  return normalized
}

function normalizeMetadataFilters(filters: Readonly<Record<string, string>> | undefined): Readonly<Record<string, string>> {
  if (filters === undefined) {
    return {}
  }

  const normalized: Record<string, string> = {}
  for (const key of Object.keys(filters).sort()) {
    const value = filters[key]
    if (value !== undefined) {
      normalized[key] = value
    }
  }
  return normalized
}

function normalizeArtifactTypes(types: readonly DashboardArtifactType[] | undefined): readonly DashboardArtifactType[] {
  return types === undefined ? [] : Array.from(new Set(types)).sort()
}

function withQuery(path: string, append: (params: URLSearchParams) => void): string {
  const params = new URLSearchParams()
  append(params)
  const query = params.toString()
  return query.length === 0 ? path : `${path}?${query}`
}

function appendPagination(params: URLSearchParams, request: NormalizedPagination) {
  appendValue(params, 'limit', request.limit)
  appendValue(params, 'cursor', request.cursor)
}

function appendArtifactListParameters(params: URLSearchParams, request: NormalizedArtifactListRequest) {
  appendValue(params, 'projectId', request.projectId)
  appendValue(params, 'iterationId', request.iterationId)
  appendValue(params, 'sourceProjectId', request.sourceProjectId)
  appendValue(params, 'sourceIterationId', request.sourceIterationId)
  appendValue(params, 'sourceDocumentId', request.sourceDocumentId)
  appendValue(params, 'sourceTaskGraphId', request.sourceTaskGraphId)
  appendValue(params, 'sourceTaskId', request.sourceTaskId)
  appendValue(params, 'sourceRunId', request.sourceRunId)
  appendValue(params, 'artifactType', request.artifactType)
  for (const artifactType of request.artifactTypes) {
    params.append('artifactTypes', artifactType)
  }
  appendValue(params, 'sourcePath', request.sourcePath)
  appendValue(params, 'taskId', request.taskId)
  appendValue(params, 'runId', request.runId)
  appendValue(params, 'contentHash', request.contentHash)
  appendValue(params, 'sourceReferenceCanonicalServerId', request.sourceReferenceCanonicalServerId)
  appendValue(params, 'sourceReferenceUri', request.sourceReferenceUri)
  appendPagination(params, request)
}

function appendKeywordSearchParameters(params: URLSearchParams, request: NormalizedKeywordSearchRequest) {
  appendValue(params, 'q', request.q)
  appendSearchFilters(params, request.filters)
}

function appendSearchFilters(params: URLSearchParams, filters: NormalizedSearchFilters) {
  appendValue(params, 'projectId', filters.projectId)
  appendValue(params, 'iterationId', filters.iterationId)
  appendValue(params, 'artifactType', filters.artifactType)
  appendValue(params, 'sourcePath', filters.sourcePath)
  appendValue(params, 'taskId', filters.taskId)
  appendValue(params, 'runId', filters.runId)
  appendPagination(params, filters)
}

function appendGraphNodeParameters(params: URLSearchParams, request: NormalizedGraphNodeListRequest) {
  appendValue(params, 'projectId', request.projectId)
  appendValue(params, 'iterationId', request.iterationId)
  appendValue(params, 'nodeKind', request.nodeKind)
  appendValue(params, 'query', request.query)
  appendValue(params, 'limit', request.limit)
}

function appendGraphTraceParameters(params: URLSearchParams, request: NormalizedGraphTraceRequest) {
  appendValue(params, 'projectId', request.projectId)
  appendValue(params, 'naturalKey', request.naturalKey)
  appendValue(params, 'iterationId', request.iterationId)
  appendValue(params, 'direction', request.direction)
  appendValue(params, 'maxDepth', request.maxDepth)
}

function appendValue(params: URLSearchParams, name: string, value: number | string | null) {
  if (value !== null) {
    params.append(name, String(value))
  }
}

function serializeSemanticSearchRequest(request: NormalizedSemanticSearchRequest): string {
  return JSON.stringify({
    q: request.q,
    ...serializeSearchFilters(request.filters),
    ...(Object.keys(request.metadataFilters).length === 0 ? {} : { metadataFilters: request.metadataFilters }),
  })
}

function serializeHybridSearchRequest(request: NormalizedHybridSearchRequest): string {
  return JSON.stringify({
    q: request.q,
    ...serializeSearchFilters(request.filters),
    ...(Object.keys(request.metadataFilters).length === 0 ? {} : { metadataFilters: request.metadataFilters }),
    ...(request.rrfK === null ? {} : { rrfK: request.rrfK }),
    ...(request.candidateLimit === null ? {} : { candidateLimit: request.candidateLimit }),
  })
}

function serializeSearchFilters(filters: NormalizedSearchFilters): Record<string, number | string> {
  const serialized: Record<string, number | string> = {}
  appendObjectValue(serialized, 'projectId', filters.projectId)
  appendObjectValue(serialized, 'iterationId', filters.iterationId)
  appendObjectValue(serialized, 'artifactType', filters.artifactType)
  appendObjectValue(serialized, 'sourcePath', filters.sourcePath)
  appendObjectValue(serialized, 'taskId', filters.taskId)
  appendObjectValue(serialized, 'runId', filters.runId)
  appendObjectValue(serialized, 'limit', filters.limit)
  appendObjectValue(serialized, 'cursor', filters.cursor)
  return serialized
}

function appendObjectValue(target: Record<string, number | string>, name: string, value: number | string | null) {
  if (value !== null) {
    target[name] = value
  }
}

async function getJson<T>(path: string, options: ApiRequestOptions | undefined, decode: (value: unknown) => T, fetchImpl: typeof fetch): Promise<T> {
  return requestJson(path, { headers: { accept: 'application/json' }, method: 'GET' }, options, decode, fetchImpl)
}

async function postJson<T>(path: string, body: string, options: ApiRequestOptions | undefined, decode: (value: unknown) => T, fetchImpl: typeof fetch): Promise<T> {
  return requestJson(
    path,
    { body, headers: { accept: 'application/json', 'content-type': 'application/json' }, method: 'POST' },
    options,
    decode,
    fetchImpl,
  )
}

async function requestJson<T>(path: string, init: RequestInit, options: ApiRequestOptions | undefined, decode: (value: unknown) => T, fetchImpl: typeof fetch): Promise<T> {
  let response: Response
  try {
    response = await fetchImpl(path, { ...init, signal: options?.signal })
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    throw publicError('network_error', null)
  }

  if (!response.ok) {
    throw publicErrorForStatus(response.status, await readPublicErrorCode(response))
  }

  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    throw publicError('invalid_response', response.status)
  }

  try {
    return decode(payload)
  } catch (error) {
    if (isDashboardApiError(error)) {
      throw error
    }
    throw publicError('invalid_response', response.status)
  }
}

function isAbortError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError'
}

async function readPublicErrorCode(response: Response): Promise<DashboardApiErrorCode | null> {
  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    return null
  }

  if (!isRecord(payload) || typeof payload.error !== 'string') {
    return null
  }
  return toPublicErrorCode(payload.error)
}

function publicErrorForStatus(status: number, responseCode: DashboardApiErrorCode | null): DashboardApiError {
  if (responseCode !== null) {
    return publicError(responseCode, status)
  }

  switch (status) {
    case 400:
      return publicError('invalid_request', status)
    case 401:
      return publicError('authentication_required', status)
    case 403:
      return publicError('access_denied', status)
    case 404:
      return publicError('not_found', status)
    case 409:
      return publicError('conflict', status)
    case 413:
      return publicError('request_too_large', status)
    case 502:
    case 503:
      return publicError('service_unavailable', status)
    case 504:
      return publicError('service_timeout', status)
    default:
      return publicError('request_failed', status)
  }
}

function toPublicErrorCode(value: string): DashboardApiErrorCode | null {
  switch (value) {
    case 'bff_backend_unavailable':
    case 'bff_forbidden_route':
    case 'bff_response_too_large':
    case 'bff_timeout':
    case 'embedding_provider_not_configured':
    case 'embedding_provider_unavailable':
      return value
    default:
      return null
  }
}

function publicError(code: DashboardApiErrorCode, status: number | null): DashboardApiError {
  const messages: Readonly<Record<DashboardApiErrorCode, string>> = {
    access_denied: 'The dashboard service denied this request.',
    authentication_required: 'The dashboard service requires authentication.',
    bff_backend_unavailable: 'The dashboard backend is unavailable.',
    bff_forbidden_route: 'The dashboard backend rejected this request.',
    bff_response_too_large: 'The dashboard backend response is too large.',
    bff_timeout: 'The dashboard backend timed out.',
    conflict: 'The requested data conflicts with the current server state.',
    embedding_provider_not_configured: 'Semantic search is unavailable because its embedding provider is not configured.',
    embedding_provider_unavailable: 'Semantic search is temporarily unavailable because its embedding provider is unavailable.',
    invalid_request: 'The request could not be processed.',
    invalid_response: 'The dashboard service returned an invalid response.',
    network_error: 'Unable to reach the dashboard service.',
    not_found: 'The requested dashboard data was not found.',
    request_failed: 'The dashboard request failed.',
    request_too_large: 'The dashboard request is too large.',
    service_timeout: 'The dashboard service timed out.',
    service_unavailable: 'The dashboard service is unavailable.',
  }
  return new DashboardApiError(code, status, messages[code])
}

function parseHealthResponse(value: unknown): HealthResponse {
  const record = requireRecord(value)
  return {
    status: requireString(record, 'status'),
    timestamp: requireString(record, 'timestamp'),
  }
}

function parseProjectPage(value: unknown): Page<ProjectSummary> {
  return parsePage(value, parseProjectSummary)
}

function parseIterationPage(value: unknown): Page<IterationSummary> {
  return parsePage(value, parseIterationSummary)
}

function parseArtifactLookupPage(value: unknown): Page<ArtifactLookupItem> {
  return parsePage(value, parseArtifactLookupItem)
}

function parseKeywordSearchPage(value: unknown): Page<KeywordSearchItem> {
  return parsePage(value, parseKeywordSearchItem)
}

function parseSemanticSearchPage(value: unknown): Page<SemanticSearchItem> {
  return parsePage(value, parseSemanticSearchItem)
}

function parseHybridSearchPage(value: unknown): Page<HybridSearchItem> {
  return parsePage(value, parseHybridSearchItem)
}

function parsePage<T>(value: unknown, parseItem: (item: unknown) => T): Page<T> {
  const record = requireRecord(value)
  return {
    items: requireArray(record, 'items').map((item: unknown) => parseItem(item)),
    nextCursor: optionalString(record, 'nextCursor'),
  }
}

function parseProjectSummary(value: unknown): ProjectSummary {
  const record = requireRecord(value)
  return {
    projectId: requireString(record, 'projectId'),
    sourceProjectId: requireString(record, 'sourceProjectId'),
    name: requireString(record, 'name'),
    canonicalServerId: requireString(record, 'canonicalServerId'),
    rootPath: requireString(record, 'rootPath'),
    sourceReference: optionalObject(record, 'sourceReference', parseSourceReference),
    createdAt: requireString(record, 'createdAt'),
    updatedAt: optionalString(record, 'updatedAt'),
    metadata: optionalStringRecord(record, 'metadata'),
  }
}

function parseIterationSummary(value: unknown): IterationSummary {
  const record = requireRecord(value)
  return {
    iterationId: requireString(record, 'iterationId'),
    projectId: requireString(record, 'projectId'),
    sourceIterationId: requireString(record, 'sourceIterationId'),
    label: requireString(record, 'label'),
    status: requireString(record, 'status'),
    sourceReference: optionalObject(record, 'sourceReference', parseSourceReference),
    createdAt: requireString(record, 'createdAt'),
    updatedAt: optionalString(record, 'updatedAt'),
    metadata: optionalStringRecord(record, 'metadata'),
  }
}

function parseArtifactDetail(value: unknown): ArtifactDetail {
  const record = requireRecord(value)
  return {
    artifactType: parseDashboardArtifactType(requireString(record, 'artifactType')),
    artifactId: requireString(record, 'artifactId'),
    projectId: requireString(record, 'projectId'),
    iterationId: optionalString(record, 'iterationId'),
    title: requireString(record, 'title'),
    source: parseArtifactSource(requireField(record, 'source')),
    lineage: parseArtifactLineage(requireField(record, 'lineage')),
    mediaType: requireString(record, 'mediaType'),
    rawContent: requireString(record, 'rawContent'),
    createdAt: optionalString(record, 'createdAt'),
    updatedAt: optionalString(record, 'updatedAt'),
    metadata: optionalStringRecord(record, 'metadata'),
  }
}

function parseArtifactLookupItem(value: unknown): ArtifactLookupItem {
  const record = requireRecord(value)
  return {
    artifactType: requireString(record, 'artifactType'),
    artifactId: requireString(record, 'artifactId'),
    projectId: requireString(record, 'projectId'),
    iterationId: optionalString(record, 'iterationId'),
    taskId: optionalString(record, 'taskId'),
    runId: optionalString(record, 'runId'),
    sourcePath: optionalString(record, 'sourcePath'),
    title: requireString(record, 'title'),
    contentHash: optionalString(record, 'contentHash'),
    snapshotVersion: optionalNumber(record, 'snapshotVersion'),
    lineage: parseArtifactLookupLineage(requireField(record, 'lineage')),
    sourceIds: parseSourceIds(requireField(record, 'sourceIds')),
    sourceReference: optionalObject(record, 'sourceReference', parseSourceReference),
    createdAt: optionalString(record, 'createdAt'),
    updatedAt: optionalString(record, 'updatedAt'),
    metadata: optionalStringRecord(record, 'metadata'),
  }
}

function parseArtifactSource(value: unknown): ArtifactSource {
  const record = requireRecord(value)
  return {
    sourceProjectId: optionalString(record, 'sourceProjectId'),
    sourceIterationId: optionalString(record, 'sourceIterationId'),
    sourceDocumentId: optionalString(record, 'sourceDocumentId'),
    sourceTaskGraphId: optionalString(record, 'sourceTaskGraphId'),
    sourceTaskId: optionalString(record, 'sourceTaskId'),
    sourceRunId: optionalString(record, 'sourceRunId'),
    sourcePath: optionalString(record, 'sourcePath'),
    sourceReference: optionalObject(record, 'sourceReference', parseSourceReference),
  }
}

function parseArtifactLineage(value: unknown): ArtifactLineage {
  const record = requireRecord(value)
  return {
    projectId: requireString(record, 'projectId'),
    iterationId: optionalString(record, 'iterationId'),
    documentId: optionalString(record, 'documentId'),
    taskGraphId: optionalString(record, 'taskGraphId'),
    taskId: optionalString(record, 'taskId'),
    runId: optionalString(record, 'runId'),
    contentHash: optionalString(record, 'contentHash'),
    snapshotVersion: optionalNumber(record, 'snapshotVersion'),
    artifactRefs: optionalArray(record, 'artifactRefs').map((item: unknown) => parseArtifactReference(item)),
  }
}

function parseArtifactReference(value: unknown): ArtifactReference {
  const record = requireRecord(value)
  return {
    artifactType: requireString(record, 'artifactType'),
    artifactId: requireString(record, 'artifactId'),
    sourcePath: optionalString(record, 'sourcePath'),
  }
}

function parseArtifactLookupLineage(value: unknown): ArtifactLookupLineage {
  const record = requireRecord(value)
  return {
    projectId: requireString(record, 'projectId'),
    iterationId: optionalString(record, 'iterationId'),
    sourcePath: optionalString(record, 'sourcePath'),
    contentHash: optionalString(record, 'contentHash'),
    snapshotVersion: optionalNumber(record, 'snapshotVersion'),
    taskId: optionalString(record, 'taskId'),
    runId: optionalString(record, 'runId'),
  }
}

function parseKeywordSearchItem(value: unknown): KeywordSearchItem {
  const record = requireRecord(value)
  return {
    ...parseSearchItemBase(record),
    matchReason: requireString(record, 'matchReason'),
  }
}

function parseSemanticSearchItem(value: unknown): SemanticSearchItem {
  const record = requireRecord(value)
  return {
    ...parseSearchItemBase(record),
    distanceMetric: requireString(record, 'distanceMetric'),
    embeddingModel: requireString(record, 'embeddingModel'),
    embeddingVersion: requireString(record, 'embeddingVersion'),
  }
}

function parseHybridSearchItem(value: unknown): HybridSearchItem {
  const record = requireRecord(value)
  return {
    ...parseSearchItemBase(record),
    matchReason: requireString(record, 'matchReason'),
    keyword: optionalObject(record, 'keyword', parseHybridSearchArm),
    vector: optionalObject(record, 'vector', parseHybridSearchArm),
  }
}

function parseSearchItemBase(record: Record<string, unknown>): SearchItemBase {
  return {
    chunkId: optionalString(record, 'chunkId'),
    documentId: optionalString(record, 'documentId'),
    projectId: requireString(record, 'projectId'),
    iterationId: optionalString(record, 'iterationId'),
    artifactType: requireString(record, 'artifactType'),
    sourcePath: optionalString(record, 'sourcePath'),
    chunkIndex: optionalNumber(record, 'chunkIndex'),
    content: requireString(record, 'content'),
    score: requireNumber(record, 'score'),
    lineage: parseSearchLineage(requireField(record, 'lineage')),
    sourceIds: parseSourceIds(requireField(record, 'sourceIds')),
    sourceReference: optionalObject(record, 'sourceReference', parseSourceReference),
    citation: parseSearchCitation(requireField(record, 'citation')),
    metadata: optionalStringRecord(record, 'metadata'),
  }
}

function parseSearchLineage(value: unknown): SearchLineage {
  const record = requireRecord(value)
  return {
    projectId: requireString(record, 'projectId'),
    iterationId: optionalString(record, 'iterationId'),
    documentId: optionalString(record, 'documentId'),
    chunkId: optionalString(record, 'chunkId'),
    sourcePath: optionalString(record, 'sourcePath'),
    chunkIndex: optionalNumber(record, 'chunkIndex'),
  }
}

function parseSearchCitation(value: unknown): SearchCitation {
  const record = requireRecord(value)
  return {
    lineage: parseSearchLineage(requireField(record, 'lineage')),
    sourceIds: parseSourceIds(requireField(record, 'sourceIds')),
    sourceReference: optionalObject(record, 'sourceReference', parseSourceReference),
  }
}

function parseSourceIds(value: unknown): SourceIds {
  const record = requireRecord(value)
  return {
    sourceProjectId: optionalString(record, 'sourceProjectId'),
    sourceIterationId: optionalString(record, 'sourceIterationId'),
    sourceDocumentId: optionalString(record, 'sourceDocumentId'),
    sourceTaskGraphId: optionalString(record, 'sourceTaskGraphId'),
    sourceTaskId: optionalString(record, 'sourceTaskId'),
    sourceRunId: optionalString(record, 'sourceRunId'),
    sourceChunkId: optionalString(record, 'sourceChunkId'),
  }
}

function parseSourceReference(value: unknown): SourceReference {
  const record = requireRecord(value)
  return {
    canonicalServerId: requireString(record, 'canonicalServerId'),
    uri: requireString(record, 'uri'),
    path: optionalString(record, 'path'),
    startLine: optionalNumber(record, 'startLine'),
    endLine: optionalNumber(record, 'endLine'),
    fragment: optionalString(record, 'fragment'),
  }
}

function parseGraphNodes(value: unknown): readonly GraphNode[] {
  return requireUnknownArray(value).map((item: unknown) => parseGraphNode(item))
}

function parseGraphTrace(value: unknown): GraphTrace {
  const record = requireRecord(value)
  return {
    root: parseGraphNode(requireField(record, 'root')),
    nodes: requireArray(record, 'nodes').map((item: unknown) => parseGraphTraceNode(item)),
    edges: requireArray(record, 'edges').map((item: unknown) => parseGraphEdge(item)),
    truncated: requireBoolean(record, 'truncated'),
  }
}

function parseGraphNode(value: unknown): GraphNode {
  const record = requireRecord(value)
  return {
    nodeId: requireString(record, 'nodeId'),
    projectId: requireString(record, 'projectId'),
    iterationId: optionalString(record, 'iterationId'),
    nodeKind: requireString(record, 'nodeKind'),
    naturalKey: requireString(record, 'naturalKey'),
    label: requireString(record, 'label'),
    content: optionalString(record, 'content'),
    documentId: optionalString(record, 'documentId'),
    taskId: optionalString(record, 'taskId'),
    runId: optionalString(record, 'runId'),
    metadata: optionalStringRecord(record, 'metadata'),
  }
}

function parseGraphTraceNode(value: unknown): GraphTraceNode {
  const record = requireRecord(value)
  return {
    node: parseGraphNode(requireField(record, 'node')),
    depth: requireNumber(record, 'depth'),
  }
}

function parseGraphEdge(value: unknown): GraphEdge {
  const record = requireRecord(value)
  return {
    edgeId: requireString(record, 'edgeId'),
    projectId: requireString(record, 'projectId'),
    fromNodeId: requireString(record, 'fromNodeId'),
    toNodeId: requireString(record, 'toNodeId'),
    edgeType: requireString(record, 'edgeType'),
    sourceReference: optionalString(record, 'sourceReference'),
    metadata: optionalStringRecord(record, 'metadata'),
  }
}

function parseHybridSearchArm(value: unknown): HybridSearchArm {
  const record = requireRecord(value)
  return {
    rank: requireNumber(record, 'rank'),
    score: requireNumber(record, 'score'),
  }
}

function parseDashboardArtifactType(value: string): DashboardArtifactType {
  switch (value) {
    case 'DOCUMENT_SNAPSHOT':
    case 'TASK_GRAPH':
    case 'TASK':
    case 'RUN_RECORD':
    case 'PROPOSAL':
      return value
    default:
      throw publicError('invalid_response', null)
  }
}

function requireRecord(value: unknown): Record<string, unknown> {
  if (!isRecord(value)) {
    throw publicError('invalid_response', null)
  }
  return value
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requireField(record: Record<string, unknown>, field: string): unknown {
  const value = record[field]
  if (value === undefined || value === null) {
    throw publicError('invalid_response', null)
  }
  return value
}

function requireString(record: Record<string, unknown>, field: string): string {
  const value = requireField(record, field)
  if (typeof value !== 'string') {
    throw publicError('invalid_response', null)
  }
  return value
}

function optionalString(record: Record<string, unknown>, field: string): string | null {
  const value = record[field]
  if (value === undefined || value === null) {
    return null
  }
  if (typeof value !== 'string') {
    throw publicError('invalid_response', null)
  }
  return value
}

function requireNumber(record: Record<string, unknown>, field: string): number {
  const value = requireField(record, field)
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw publicError('invalid_response', null)
  }
  return value
}

function optionalNumber(record: Record<string, unknown>, field: string): number | null {
  const value = record[field]
  if (value === undefined || value === null) {
    return null
  }
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw publicError('invalid_response', null)
  }
  return value
}

function requireBoolean(record: Record<string, unknown>, field: string): boolean {
  const value = requireField(record, field)
  if (typeof value !== 'boolean') {
    throw publicError('invalid_response', null)
  }
  return value
}

function requireArray(record: Record<string, unknown>, field: string): unknown[] {
  return requireUnknownArray(requireField(record, field))
}

function optionalArray(record: Record<string, unknown>, field: string): unknown[] {
  const value = record[field]
  return value === undefined || value === null ? [] : requireUnknownArray(value)
}

function requireUnknownArray(value: unknown): unknown[] {
  if (!Array.isArray(value)) {
    throw publicError('invalid_response', null)
  }
  return value
}

function optionalObject<T>(record: Record<string, unknown>, field: string, parse: (value: unknown) => T): T | null {
  const value = record[field]
  return value === undefined || value === null ? null : parse(value)
}

function optionalStringRecord(record: Record<string, unknown>, field: string): Readonly<Record<string, string>> {
  const value = record[field]
  if (value === undefined || value === null) {
    return {}
  }
  const source = requireRecord(value)
  const result: Record<string, string> = {}
  for (const [key, entry] of Object.entries(source)) {
    if (typeof entry !== 'string') {
      throw publicError('invalid_response', null)
    }
    result[key] = entry
  }
  return result
}
