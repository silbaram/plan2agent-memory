import type { ArtifactLookupItem, DashboardArtifactType, Page } from '../data-access'

export type PriorityDocumentKind =
  | 'product_spec'
  | 'implementation_plan'
  | 'task_graph'
  | 'visual_experience'
  | 'final_review'

export type ExplicitArtifactStatus = 'approved' | 'draft' | 'pending' | 'unknown' | 'conflict'

export interface ArtifactIdentity {
  readonly artifactType: DashboardArtifactType
  readonly artifactId: string
}

export interface DocumentSummary {
  readonly title: string
  readonly purpose: string
  readonly summary: string
}

export interface DocumentOutlineEntry {
  readonly headingLevel: number
  readonly label: string
  readonly fragmentId: string
}

export interface PriorityDocument {
  readonly canonicalKind: PriorityDocumentKind
  readonly label: string
  readonly purpose: string
  readonly sourcePath: string
  readonly identity: ArtifactIdentity
  readonly gate: string
  readonly explicitStatus: ExplicitArtifactStatus
  readonly linkedWorkCount: number
}

export interface PriorityDocumentLookupScope {
  readonly projectId: string
  readonly iterationId: string
  readonly sourceIterationId: string
}

export interface PriorityDocumentSource {
  readonly artifactType: 'DOCUMENT_SNAPSHOT' | 'TASK_GRAPH'
  readonly canonicalKind: PriorityDocumentKind
  readonly gate: string
  readonly label: string
  readonly purpose: string
  readonly sourcePath: string
}

export interface PriorityDocumentClassification {
  readonly priorityDocuments: readonly PriorityDocument[]
  readonly selectedPriorityDocument: PriorityDocument | null
  readonly supportingDocuments: readonly ArtifactLookupItem[]
}

export interface PriorityDocumentClassificationInput {
  readonly currentPage?: Page<ArtifactLookupItem> | null
  readonly lookup: readonly Page<ArtifactLookupItem>[]
  readonly scope: PriorityDocumentLookupScope
  readonly selection?: ArtifactIdentity | null
}

export const priorityDocumentSources = [
  {
    artifactType: 'DOCUMENT_SNAPSHOT',
    canonicalKind: 'product_spec',
    gate: 'Gate B',
    label: 'Product specification',
    purpose: 'Defines the approved product outcome for this iteration.',
    sourcePath: 'gate-b-spec/product-spec.md',
  },
  {
    artifactType: 'DOCUMENT_SNAPSHOT',
    canonicalKind: 'implementation_plan',
    gate: 'Gate B',
    label: 'Implementation plan',
    purpose: 'Describes the approved implementation approach for this iteration.',
    sourcePath: 'gate-b-spec/implementation-plan.md',
  },
  {
    artifactType: 'TASK_GRAPH',
    canonicalKind: 'task_graph',
    gate: 'Gate C',
    label: 'Task graph',
    purpose: 'Defines the dependency-aware implementation tasks for this iteration.',
    sourcePath: 'gate-c-task-graph/task-graph.json',
  },
  {
    artifactType: 'DOCUMENT_SNAPSHOT',
    canonicalKind: 'visual_experience',
    gate: 'Gate B',
    label: 'Experience specification',
    purpose: 'Defines the approved visual experience when this iteration has one.',
    sourcePath: 'gate-b-spec/experience-spec.json',
  },
  {
    artifactType: 'DOCUMENT_SNAPSHOT',
    canonicalKind: 'final_review',
    gate: 'Gate D',
    label: 'Final review',
    purpose: 'Records the canonical final review for this iteration.',
    sourcePath: 'gate-d-review/review.json',
  },
] as const satisfies readonly PriorityDocumentSource[]

export interface GateProgressItem {
  readonly gate: string
  readonly label: string
  readonly explicitStatus: ExplicitArtifactStatus
  readonly isCurrent?: boolean
  readonly detail?: string
}

export interface LinkedWorkItem {
  readonly identity: ArtifactIdentity
  readonly taskId?: string
  readonly runId?: string
  readonly label: string
  readonly relation: string
  readonly status: string
  readonly href?: string
}

export interface TaskGraphStatusCount {
  readonly status: string
  readonly count: number
}

export interface TaskGraphSummary {
  readonly taskCount: number
  readonly dependencyCount: number
  readonly statusCounts: readonly TaskGraphStatusCount[]
  readonly sourceSpecRefs: readonly string[]
  readonly currentTaskId?: string
}

export interface RecentCompletedWork {
  readonly work: LinkedWorkItem
  readonly completedAt?: string
  readonly result?: string
}

export interface ArtifactPresentationContent {
  readonly documentSummary?: DocumentSummary
  readonly documentOutline?: readonly DocumentOutlineEntry[]
  readonly priorityDocuments?: readonly PriorityDocument[]
  readonly gateProgress?: readonly GateProgressItem[]
  readonly taskGraphSummary?: TaskGraphSummary
  readonly recentCompletedWork?: readonly RecentCompletedWork[]
}

export interface ArtifactPresentationMetadata {
  readonly linkedWork?: readonly LinkedWorkItem[]
}

export function parseArtifactPresentationContent(rawContent: unknown): ArtifactPresentationContent | null {
  if (typeof rawContent !== 'string') {
    return null
  }

  let content: unknown
  try {
    content = JSON.parse(rawContent)
  } catch {
    return null
  }

  return isArtifactPresentationContent(content) ? content : null
}

export function parseArtifactPresentationMetadata(metadata: unknown): ArtifactPresentationMetadata | null {
  return isArtifactPresentationMetadata(metadata) ? metadata : null
}

export function normalizePriorityDocumentLookupScope(scope: PriorityDocumentLookupScope): PriorityDocumentLookupScope {
  return {
    iterationId: requireScopeValue(scope.iterationId, 'iterationId'),
    projectId: requireScopeValue(scope.projectId, 'projectId'),
    sourceIterationId: requirePathSegment(scope.sourceIterationId, 'sourceIterationId'),
  }
}

export function normalizePriorityDocumentSourcePath(sourcePath: string | null, sourceIterationId: string): string | null {
  if (sourcePath === null) {
    return null
  }

  const segments = sourcePath.trim().replaceAll('\\', '/').split('/')
  const normalizedSegments: string[] = []
  for (const segment of segments) {
    if (segment.length === 0 || segment === '.') {
      continue
    }
    if (segment === '..') {
      return null
    }
    normalizedSegments.push(segment)
  }

  const normalizedPath = normalizedSegments.join('/')
  const normalizedSourceIterationId = sourceIterationId.trim()
  const iterationPrefix = `iterations/${normalizedSourceIterationId}/`
  return normalizedPath.startsWith(iterationPrefix)
    ? normalizedPath.slice(iterationPrefix.length)
    : normalizedPath
}

export function classifyPriorityDocuments({
  currentPage = null,
  lookup,
  scope: scopeInput,
  selection = null,
}: PriorityDocumentClassificationInput): PriorityDocumentClassification {
  const scope = normalizePriorityDocumentLookupScope(scopeInput)
  const lookupItems = lookup.flatMap((page) => page.items)
  const currentPageItems = currentPage?.items ?? []
  const candidates = uniqueScopedArtifacts([...lookupItems, ...currentPageItems], scope)
  const priorityEntries = priorityDocumentSources.flatMap((source) => {
    const matches = candidates.filter((artifact) => matchesPriorityDocumentSource(artifact, source, scope.sourceIterationId))
    const selectedMatch = selection === null
      ? undefined
      : matches.find((artifact) => artifactMatchesIdentity(artifact, selection))
    const artifact = selectedMatch ?? matches[0]
    return artifact === undefined ? [] : [toPriorityDocument(source, artifact)]
  })
  const priorityIdentities = new Set(priorityEntries.map((document) => artifactIdentityKey(document.identity)))
  const selectedPriorityDocument = selection === null
    ? null
    : priorityEntries.find((document) => artifactIdentityKey(document.identity) === artifactIdentityKey(selection)) ?? null

  return {
    priorityDocuments: priorityEntries,
    selectedPriorityDocument,
    supportingDocuments: candidates.filter((artifact) => !priorityIdentities.has(artifactIdentityKey(artifact))),
  }
}

export function isArtifactPresentationContent(value: unknown): value is ArtifactPresentationContent {
  return isRecord(value)
    && hasOptionalValue(value, 'documentSummary', isDocumentSummary)
    && hasOptionalArray(value, 'documentOutline', isDocumentOutlineEntry)
    && hasOptionalArray(value, 'priorityDocuments', isPriorityDocument)
    && hasOptionalArray(value, 'gateProgress', isGateProgressItem)
    && hasOptionalValue(value, 'taskGraphSummary', isTaskGraphSummary)
    && hasOptionalArray(value, 'recentCompletedWork', isRecentCompletedWork)
}

export function isArtifactPresentationMetadata(value: unknown): value is ArtifactPresentationMetadata {
  return isRecord(value)
    && hasOptionalArray(value, 'linkedWork', isLinkedWorkItem)
}

export function isArtifactIdentity(value: unknown): value is ArtifactIdentity {
  return isRecord(value)
    && isDashboardArtifactType(value.artifactType)
    && hasRequiredString(value, 'artifactId')
}

export function isDocumentSummary(value: unknown): value is DocumentSummary {
  return isRecord(value)
    && hasRequiredString(value, 'title')
    && hasRequiredString(value, 'purpose')
    && hasRequiredString(value, 'summary')
}

export function isDocumentOutlineEntry(value: unknown): value is DocumentOutlineEntry {
  return isRecord(value)
    && hasRequiredIntegerInRange(value, 'headingLevel', 1, 6)
    && hasRequiredString(value, 'label')
    && hasRequiredString(value, 'fragmentId')
}

export function isPriorityDocument(value: unknown): value is PriorityDocument {
  return isRecord(value)
    && isPriorityDocumentKind(value.canonicalKind)
    && hasRequiredString(value, 'label')
    && hasRequiredString(value, 'purpose')
    && hasRequiredString(value, 'sourcePath')
    && isArtifactIdentity(value.identity)
    && hasRequiredString(value, 'gate')
    && isExplicitArtifactStatus(value.explicitStatus)
    && hasRequiredNonNegativeInteger(value, 'linkedWorkCount')
}

export function isGateProgressItem(value: unknown): value is GateProgressItem {
  return isRecord(value)
    && hasRequiredString(value, 'gate')
    && hasRequiredString(value, 'label')
    && isExplicitArtifactStatus(value.explicitStatus)
    && hasOptionalBoolean(value, 'isCurrent')
    && hasOptionalString(value, 'detail')
}

export function isTaskGraphSummary(value: unknown): value is TaskGraphSummary {
  return isRecord(value)
    && hasRequiredNonNegativeInteger(value, 'taskCount')
    && hasRequiredNonNegativeInteger(value, 'dependencyCount')
    && isArrayOf(value.statusCounts, isTaskGraphStatusCount)
    && isArrayOf(value.sourceSpecRefs, isString)
    && hasOptionalString(value, 'currentTaskId')
}

export function isLinkedWorkItem(value: unknown): value is LinkedWorkItem {
  return isRecord(value)
    && isArtifactIdentity(value.identity)
    && hasOptionalString(value, 'taskId')
    && hasOptionalString(value, 'runId')
    && hasRequiredString(value, 'label')
    && hasRequiredString(value, 'relation')
    && hasRequiredString(value, 'status')
    && hasOptionalString(value, 'href')
}

export function isRecentCompletedWork(value: unknown): value is RecentCompletedWork {
  return isRecord(value)
    && isLinkedWorkItem(value.work)
    && hasOptionalString(value, 'completedAt')
    && hasOptionalString(value, 'result')
}

function isTaskGraphStatusCount(value: unknown): value is TaskGraphStatusCount {
  return isRecord(value)
    && hasRequiredString(value, 'status')
    && hasRequiredNonNegativeInteger(value, 'count')
}

function isDashboardArtifactType(value: unknown): value is DashboardArtifactType {
  return value === 'DOCUMENT_SNAPSHOT'
    || value === 'TASK_GRAPH'
    || value === 'TASK'
    || value === 'RUN_RECORD'
    || value === 'PROPOSAL'
}

function isPriorityDocumentKind(value: unknown): value is PriorityDocumentKind {
  return value === 'product_spec'
    || value === 'implementation_plan'
    || value === 'task_graph'
    || value === 'visual_experience'
    || value === 'final_review'
}

function uniqueScopedArtifacts(
  artifacts: readonly ArtifactLookupItem[],
  scope: PriorityDocumentLookupScope,
): readonly ArtifactLookupItem[] {
  const identities = new Set<string>()
  const uniqueArtifacts: ArtifactLookupItem[] = []
  for (const artifact of artifacts) {
    if (artifact.projectId !== scope.projectId || artifact.iterationId !== scope.iterationId) {
      continue
    }
    const identity = artifactIdentityKey(artifact)
    if (!identities.has(identity)) {
      identities.add(identity)
      uniqueArtifacts.push(artifact)
    }
  }
  return uniqueArtifacts
}

function matchesPriorityDocumentSource(
  artifact: ArtifactLookupItem,
  source: PriorityDocumentSource,
  sourceIterationId: string,
): boolean {
  return artifact.artifactType === source.artifactType
    && normalizePriorityDocumentSourcePath(artifact.sourcePath, sourceIterationId) === source.sourcePath
}

function toPriorityDocument(source: PriorityDocumentSource, artifact: ArtifactLookupItem): PriorityDocument {
  return {
    canonicalKind: source.canonicalKind,
    explicitStatus: 'unknown',
    gate: source.gate,
    identity: {
      artifactId: artifact.artifactId,
      artifactType: source.artifactType,
    },
    label: source.label,
    linkedWorkCount: 0,
    purpose: source.purpose,
    sourcePath: source.sourcePath,
  }
}

interface ArtifactIdentityLike {
  readonly artifactId: string
  readonly artifactType: string
}

function artifactMatchesIdentity(artifact: ArtifactLookupItem, identity: ArtifactIdentity): boolean {
  return artifactIdentityKey(artifact) === artifactIdentityKey(identity)
}

function artifactIdentityKey(identity: ArtifactIdentityLike): string {
  return `${identity.artifactType}:${identity.artifactId}`
}

function requireScopeValue(value: string, field: string): string {
  const normalized = value.trim()
  if (normalized.length === 0) {
    throw new Error(`${field} must not be blank`)
  }
  return normalized
}

function requirePathSegment(value: string, field: string): string {
  const normalized = requireScopeValue(value, field)
  if (normalized === '.' || normalized === '..' || normalized.includes('/') || normalized.includes('\\')) {
    throw new Error(`${field} must be one path segment`)
  }
  return normalized
}

function isExplicitArtifactStatus(value: unknown): value is ExplicitArtifactStatus {
  return value === 'approved'
    || value === 'draft'
    || value === 'pending'
    || value === 'unknown'
    || value === 'conflict'
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function hasRequiredString(record: Readonly<Record<string, unknown>>, field: string): boolean {
  return typeof record[field] === 'string'
}

function hasOptionalString(record: Readonly<Record<string, unknown>>, field: string): boolean {
  const value = record[field]
  return value === undefined || typeof value === 'string'
}

function hasOptionalBoolean(record: Readonly<Record<string, unknown>>, field: string): boolean {
  const value = record[field]
  return value === undefined || typeof value === 'boolean'
}

function hasRequiredNonNegativeInteger(record: Readonly<Record<string, unknown>>, field: string): boolean {
  const value = record[field]
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function hasRequiredIntegerInRange(
  record: Readonly<Record<string, unknown>>,
  field: string,
  minimum: number,
  maximum: number,
): boolean {
  const value = record[field]
  return typeof value === 'number'
    && Number.isSafeInteger(value)
    && value >= minimum
    && value <= maximum
}

function hasOptionalValue<T>(
  record: Readonly<Record<string, unknown>>,
  field: string,
  guard: (value: unknown) => value is T,
): boolean {
  const value = record[field]
  return value === undefined || guard(value)
}

function hasOptionalArray<T>(
  record: Readonly<Record<string, unknown>>,
  field: string,
  guard: (value: unknown) => value is T,
): boolean {
  const value = record[field]
  return value === undefined || isArrayOf(value, guard)
}

function isArrayOf<T>(value: unknown, guard: (value: unknown) => value is T): value is readonly T[] {
  if (!Array.isArray(value)) {
    return false
  }

  return value.every(guard)
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}
