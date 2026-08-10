import { queryOptions } from '@tanstack/react-query'
import {
  normalizePriorityDocumentLookupScope,
  priorityDocumentSources,
  type PriorityDocumentLookupScope,
} from '../artifact-viewer/artifactPresentation'
import {
  dashboardApi,
  normalizeArtifactListRequest,
  normalizeGraphNodeListRequest,
  normalizeGraphTraceRequest,
  normalizeHybridSearchRequest,
  normalizeKeywordSearchRequest,
  normalizePagination,
  normalizeSemanticSearchRequest,
  type DashboardApiClient,
} from './api'
import type {
  ArtifactDetailRequest,
  ArtifactListRequest,
  GraphNodeListRequest,
  GraphTraceRequest,
  HybridSearchRequest,
  IterationListRequest,
  KeywordSearchRequest,
  ProjectListRequest,
  SemanticSearchRequest,
} from './types'

export const priorityDocumentLookupPageLimit = 10

export const dashboardQueryKeys = {
  health: () => ['dashboard', 'health'] as const,
  projects: (request: ProjectListRequest = {}) => ['dashboard', 'projects', normalizePagination(request)] as const,
  projectIterations: (request: IterationListRequest) => [
    'dashboard',
    'projects',
    request.projectId.trim(),
    'iterations',
    normalizePagination(request),
  ] as const,
  artifacts: (request: ArtifactListRequest = {}) => ['dashboard', 'artifacts', normalizeArtifactListRequest(request)] as const,
  priorityDocumentLookup: (scope: PriorityDocumentLookupScope) => [
    'dashboard',
    'priority-documents',
    normalizePriorityDocumentLookupScope(scope),
  ] as const,
  artifact: (request: ArtifactDetailRequest) => ['dashboard', 'artifacts', request.artifactType, request.artifactId.trim()] as const,
  keywordSearch: (request: KeywordSearchRequest) => {
    const normalized = normalizeKeywordSearchRequest(request)
    return ['dashboard', 'search', 'keyword', normalized.q, normalized.filters] as const
  },
  semanticSearch: (request: SemanticSearchRequest) => {
    const normalized = normalizeSemanticSearchRequest(request)
    return ['dashboard', 'search', 'semantic', normalized.q, normalized.filters, normalized.metadataFilters] as const
  },
  hybridSearch: (request: HybridSearchRequest) => {
    const normalized = normalizeHybridSearchRequest(request)
    return [
      'dashboard',
      'search',
      'hybrid',
      normalized.q,
      normalized.filters,
      normalized.metadataFilters,
      { candidateLimit: normalized.candidateLimit, rrfK: normalized.rrfK },
    ] as const
  },
  graphNodes: (request: GraphNodeListRequest = {}) => ['dashboard', 'graph', 'nodes', normalizeGraphNodeListRequest(request)] as const,
  graphTrace: (request: GraphTraceRequest) => ['dashboard', 'graph', 'trace', normalizeGraphTraceRequest(request)] as const,
}

export function createDashboardQueries(client: DashboardApiClient) {
  return {
    health: () => queryOptions({
      queryKey: dashboardQueryKeys.health(),
      queryFn: ({ signal }) => client.health({ signal }),
    }),
    projects: (request: ProjectListRequest = {}) => queryOptions({
      queryKey: dashboardQueryKeys.projects(request),
      queryFn: ({ signal }) => client.listProjects(request, { signal }),
    }),
    projectIterations: (request: IterationListRequest) => queryOptions({
      queryKey: dashboardQueryKeys.projectIterations(request),
      queryFn: ({ signal }) => client.listProjectIterations(request, { signal }),
    }),
    artifacts: (request: ArtifactListRequest = {}) => queryOptions({
      queryKey: dashboardQueryKeys.artifacts(request),
      queryFn: ({ signal }) => client.listArtifacts(request, { signal }),
    }),
    priorityDocumentLookup: (scope: PriorityDocumentLookupScope) => queryOptions({
      queryKey: dashboardQueryKeys.priorityDocumentLookup(scope),
      queryFn: ({ signal }) => Promise.all(priorityDocumentLookupRequests(scope).map(
        (request) => client.listArtifacts(request, { signal }),
      )),
    }),
    artifact: (request: ArtifactDetailRequest) => queryOptions({
      queryKey: dashboardQueryKeys.artifact(request),
      queryFn: ({ signal }) => client.getArtifact(request, { signal }),
    }),
    keywordSearch: (request: KeywordSearchRequest) => queryOptions({
      queryKey: dashboardQueryKeys.keywordSearch(request),
      queryFn: ({ signal }) => client.keywordSearch(request, { signal }),
    }),
    semanticSearch: (request: SemanticSearchRequest) => queryOptions({
      queryKey: dashboardQueryKeys.semanticSearch(request),
      queryFn: ({ signal }) => client.semanticSearch(request, { signal }),
    }),
    hybridSearch: (request: HybridSearchRequest) => queryOptions({
      queryKey: dashboardQueryKeys.hybridSearch(request),
      queryFn: ({ signal }) => client.hybridSearch(request, { signal }),
    }),
    graphNodes: (request: GraphNodeListRequest = {}) => queryOptions({
      queryKey: dashboardQueryKeys.graphNodes(request),
      queryFn: ({ signal }) => client.listGraphNodes(request, { signal }),
    }),
    graphTrace: (request: GraphTraceRequest) => queryOptions({
      queryKey: dashboardQueryKeys.graphTrace(request),
      queryFn: ({ signal }) => client.traceGraph(request, { signal }),
    }),
  }
}

export const dashboardQueries = createDashboardQueries(dashboardApi)

export function priorityDocumentLookupRequests(scopeInput: PriorityDocumentLookupScope): readonly ArtifactListRequest[] {
  const scope = normalizePriorityDocumentLookupScope(scopeInput)
  return priorityDocumentSources.map((source) => ({
    artifactType: source.artifactType,
    iterationId: scope.iterationId,
    limit: priorityDocumentLookupPageLimit,
    projectId: scope.projectId,
    sourcePath: `iterations/${scope.sourceIterationId}/${source.sourcePath}`,
  }))
}
