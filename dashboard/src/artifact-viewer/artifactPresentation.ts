import type { DashboardArtifactType } from '../data-access'

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
