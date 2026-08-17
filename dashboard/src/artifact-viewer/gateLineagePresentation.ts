import type { ArtifactDetail, ArtifactLookupItem, DashboardArtifactType } from '../data-access'
import type {
  ArtifactIdentity,
  ExplicitArtifactStatus,
  GateProgressItem,
  LinkedWorkItem,
  RecentCompletedWork,
} from './artifactPresentation'

type WorkArtifactType = 'TASK' | 'RUN_RECORD'

type WorkKind = 'task' | 'run'

export type LineageWorkUnavailableReason =
  | 'missing_lineage'
  | 'dangling_lineage'
  | 'conflicting_lineage'

export interface GateProgressInput {
  readonly approvalAudit?: unknown
  readonly artifact: ArtifactDetail | null
  readonly explicitStatus?: unknown
  readonly gate: string
  readonly isCurrent?: boolean
  readonly label: string
}

export interface LineageWorkInput {
  readonly artifact: ArtifactDetail
  readonly workArtifacts: readonly ArtifactLookupItem[]
}

export interface AvailableLineageWork {
  readonly availability: 'available'
  readonly linkedWork: readonly LinkedWorkItem[]
  readonly recentCompletedWork: readonly RecentCompletedWork[]
}

export interface UnavailableLineageWork {
  readonly availability: 'unavailable'
  readonly linkedWork: readonly []
  readonly reason: LineageWorkUnavailableReason
  readonly recentCompletedWork: readonly []
}

export type LineageWorkDerivation = AvailableLineageWork | UnavailableLineageWork

interface ApprovalAuditApproved {
  readonly status: 'approved'
}

interface ApprovalAuditAbsent {
  readonly status: 'absent'
}

interface ApprovalAuditMalformed {
  readonly status: 'malformed'
}

type ApprovalAuditState = ApprovalAuditApproved | ApprovalAuditAbsent | ApprovalAuditMalformed

interface ExplicitStatusAvailable {
  readonly status: ExplicitArtifactStatus
  readonly validity: 'available'
}

interface ExplicitStatusAbsent {
  readonly validity: 'absent'
}

interface ExplicitStatusInvalid {
  readonly validity: 'invalid'
}

type ExplicitStatusState = ExplicitStatusAvailable | ExplicitStatusAbsent | ExplicitStatusInvalid

interface DirectWorkReference {
  readonly artifactId: string
  readonly artifactType: WorkArtifactType
  readonly kind: WorkKind
  readonly relation: 'lineage_artifact_ref'
  readonly source: 'artifact_ref'
}

interface IdWorkReference {
  readonly identifier: string
  readonly kind: WorkKind
  readonly relation: 'lineage_run_id' | 'lineage_task_id' | 'source_run_id' | 'source_task_id'
  readonly source: 'lineage_id' | 'source_id'
}

type WorkReference = DirectWorkReference | IdWorkReference

interface MatchedWork {
  readonly artifact: WorkArtifact
  readonly reference: WorkReference
}

type WorkArtifact = ArtifactLookupItem & {
  readonly artifactType: WorkArtifactType
}

const workArtifactTypes = ['TASK', 'RUN_RECORD'] as const satisfies readonly WorkArtifactType[]

const completedStatuses = new Set(['done', 'finished', 'completed'])

export function deriveGateProgressItem({
  approvalAudit,
  artifact,
  explicitStatus,
  gate,
  isCurrent,
  label,
}: GateProgressInput): GateProgressItem {
  const audit = resolveApprovalAudit(approvalAudit)
  const status = resolveExplicitStatus(explicitStatus)

  if (audit.status === 'approved') {
    return gateProgressItem({ detail: 'Approval audit', explicitStatus: 'approved', gate, isCurrent, label })
  }

  if (audit.status === 'malformed') {
    if (status.validity === 'available' && status.status !== 'approved') {
      return gateProgressItem({ detail: 'Explicit status; approval audit unavailable', explicitStatus: status.status, gate, isCurrent, label })
    }

    return gateProgressItem({
      detail: status.validity === 'available' ? 'Approval audit conflicts with explicit approval' : 'Approval audit unavailable',
      explicitStatus: status.validity === 'available' ? 'conflict' : 'unknown',
      gate,
      isCurrent,
      label,
    })
  }

  if (status.validity === 'available') {
    return gateProgressItem({ detail: 'Explicit status', explicitStatus: status.status, gate, isCurrent, label })
  }

  if (status.validity === 'invalid') {
    return gateProgressItem({ detail: 'Explicit status unavailable', explicitStatus: 'conflict', gate, isCurrent, label })
  }

  return artifact === null
    ? gateProgressItem({ detail: 'Artifact unavailable', explicitStatus: 'unknown', gate, isCurrent, label })
    : gateProgressItem({ detail: 'Artifact available; approval is not recorded', explicitStatus: 'pending', gate, isCurrent, label })
}

export function deriveLineageWork({ artifact, workArtifacts }: LineageWorkInput): LineageWorkDerivation {
  const references = collectWorkReferences(artifact)
  if (references.length === 0) {
    return unavailableLineageWork('missing_lineage')
  }

  const candidates = uniqueWorkArtifacts(artifact, workArtifacts)
  const matches: MatchedWork[] = []
  for (const reference of references) {
    const matchedArtifacts = candidates.filter((candidate) => matchesWorkReference(candidate, reference))
    if (matchedArtifacts.length === 0) {
      return unavailableLineageWork('dangling_lineage')
    }
    if (matchedArtifacts.length > 1) {
      return unavailableLineageWork('conflicting_lineage')
    }
    const matchedArtifact = matchedArtifacts[0]
    if (matchedArtifact === undefined) {
      return unavailableLineageWork('dangling_lineage')
    }
    matches.push({ artifact: matchedArtifact, reference })
  }

  const linkedWork = uniqueMatchedWork(matches).map(toLinkedWorkItem)
  return {
    availability: 'available',
    linkedWork,
    recentCompletedWork: deriveRecentCompletedWork(linkedWork, candidates),
  }
}

export function deriveRecentCompletedWork(
  linkedWork: readonly LinkedWorkItem[],
  workArtifacts: readonly ArtifactLookupItem[],
): readonly RecentCompletedWork[] {
  const workByIdentity = new Map<string, ArtifactLookupItem>()
  for (const artifact of workArtifacts) {
    if (!isWorkArtifactType(artifact.artifactType)) {
      continue
    }
    const identity = workIdentityKey(artifact.artifactType, artifact.artifactId)
    if (!workByIdentity.has(identity)) {
      workByIdentity.set(identity, artifact)
    }
  }

  const completedWork: RecentCompletedWork[] = []
  for (const work of linkedWork) {
    const source = workByIdentity.get(artifactIdentityKey(work.identity))
    if (source === undefined || !isCompletedStatus(source.metadata.status)) {
      continue
    }

    const completedAt = readOptionalText(source.metadata.completedAt)
    const result = readOptionalText(source.metadata.result)
    completedWork.push({
      ...(completedAt === undefined ? {} : { completedAt }),
      ...(result === undefined ? {} : { result }),
      work,
    })
  }

  return [...completedWork].sort(compareRecentCompletedWork)
}

function collectWorkReferences(artifact: ArtifactDetail): readonly WorkReference[] {
  const references: WorkReference[] = []
  const referenceKeys = new Set<string>()
  const addReference = (reference: WorkReference) => {
    const key = reference.source === 'artifact_ref'
      ? `artifact_ref:${reference.artifactType}:${reference.artifactId}`
      : `${reference.relation}:${reference.identifier}`
    if (!referenceKeys.has(key)) {
      referenceKeys.add(key)
      references.push(reference)
    }
  }

  for (const reference of artifact.lineage.artifactRefs) {
    if (!isWorkArtifactType(reference.artifactType)) {
      continue
    }
    addReference({
      artifactId: reference.artifactId,
      artifactType: reference.artifactType,
      kind: workKindForArtifactType(reference.artifactType),
      relation: 'lineage_artifact_ref',
      source: 'artifact_ref',
    })
  }

  addOptionalIdReference(artifact.source.sourceTaskId, 'task', 'source_task_id', 'source_id', addReference)
  addOptionalIdReference(artifact.source.sourceRunId, 'run', 'source_run_id', 'source_id', addReference)
  addOptionalIdReference(artifact.lineage.taskId, 'task', 'lineage_task_id', 'lineage_id', addReference)
  addOptionalIdReference(artifact.lineage.runId, 'run', 'lineage_run_id', 'lineage_id', addReference)

  return references
}

function addOptionalIdReference(
  value: string | null,
  kind: WorkKind,
  relation: IdWorkReference['relation'],
  source: IdWorkReference['source'],
  addReference: (reference: WorkReference) => void,
): void {
  const identifier = readOptionalText(value)
  if (identifier !== undefined) {
    addReference({ identifier, kind, relation, source })
  }
}

function uniqueWorkArtifacts(artifact: ArtifactDetail, workArtifacts: readonly ArtifactLookupItem[]): readonly WorkArtifact[] {
  const identities = new Set<string>()
  const candidates: WorkArtifact[] = []
  for (const candidate of workArtifacts) {
    if (candidate.projectId !== artifact.projectId || !isWorkArtifact(candidate)) {
      continue
    }
    const identity = workIdentityKey(candidate.artifactType, candidate.artifactId)
    if (identity === workIdentityKey(artifact.artifactType, artifact.artifactId) || identities.has(identity)) {
      continue
    }
    identities.add(identity)
    candidates.push(candidate)
  }
  return candidates
}

function matchesWorkReference(candidate: WorkArtifact, reference: WorkReference): boolean {
  if (reference.source === 'artifact_ref') {
    return candidate.artifactType === reference.artifactType && candidate.artifactId === reference.artifactId
  }

  const candidateType = workArtifactTypeForKind(reference.kind)
  return candidate.artifactType === candidateType && candidateWorkIds(candidate, reference.kind).includes(reference.identifier)
}

function candidateWorkIds(candidate: WorkArtifact, kind: WorkKind): readonly string[] {
  const values = kind === 'task'
    ? [candidate.taskId, candidate.sourceIds.sourceTaskId, candidate.lineage.taskId]
    : [candidate.runId, candidate.sourceIds.sourceRunId, candidate.lineage.runId]
  return values.flatMap((value) => {
    const normalized = readOptionalText(value)
    return normalized === undefined ? [] : [normalized]
  })
}

function uniqueMatchedWork(matches: readonly MatchedWork[]): readonly MatchedWork[] {
  const identities = new Set<string>()
  const uniqueMatches: MatchedWork[] = []
  for (const match of matches) {
    const identity = workIdentityKey(match.artifact.artifactType, match.artifact.artifactId)
    if (!identities.has(identity)) {
      identities.add(identity)
      uniqueMatches.push(match)
    }
  }
  return uniqueMatches
}

function toLinkedWorkItem({ artifact, reference }: MatchedWork): LinkedWorkItem {
  const identity = {
    artifactId: artifact.artifactId,
    artifactType: artifact.artifactType,
  } as const satisfies ArtifactIdentity
  const taskId = reference.kind === 'task'
    ? referenceIdentifier(reference, candidateWorkIds(artifact, 'task'))
    : undefined
  const runId = reference.kind === 'run'
    ? referenceIdentifier(reference, candidateWorkIds(artifact, 'run'))
    : undefined

  return {
    href: `/artifact/${encodeURIComponent(identity.artifactType)}/${encodeURIComponent(identity.artifactId)}`,
    identity,
    label: artifact.title.trim().length === 0 ? artifact.artifactId : artifact.title,
    relation: reference.relation,
    status: normalizeWorkStatus(artifact.metadata.status),
    ...(taskId === undefined ? {} : { taskId }),
    ...(runId === undefined ? {} : { runId }),
  }
}

function referenceIdentifier(reference: WorkReference, candidateIds: readonly string[]): string | undefined {
  if (reference.source !== 'artifact_ref') {
    return reference.identifier
  }
  return candidateIds[0]
}

function resolveApprovalAudit(value: unknown): ApprovalAuditState {
  if (value === undefined || value === null) {
    return { status: 'absent' }
  }
  if (!isRecord(value)) {
    return { status: 'malformed' }
  }

  const approvedBy = readOptionalText(value.approved_by)
  const approvedAt = readOptionalText(value.approved_at)
  return approvedBy === undefined || approvedAt === undefined
    ? { status: 'malformed' }
    : { status: 'approved' }
}

function resolveExplicitStatus(value: unknown): ExplicitStatusState {
  if (value === undefined || value === null) {
    return { validity: 'absent' }
  }
  if (typeof value !== 'string') {
    return { validity: 'invalid' }
  }

  const normalized = value.trim().toLowerCase()
  return isExplicitArtifactStatus(normalized)
    ? { status: normalized, validity: 'available' }
    : { validity: 'invalid' }
}

function gateProgressItem({
  detail,
  explicitStatus,
  gate,
  isCurrent,
  label,
}: {
  readonly detail: string
  readonly explicitStatus: ExplicitArtifactStatus
  readonly gate: string
  readonly isCurrent: boolean | undefined
  readonly label: string
}): GateProgressItem {
  return {
    detail,
    explicitStatus,
    gate,
    ...(isCurrent === undefined ? {} : { isCurrent }),
    label,
  }
}

function unavailableLineageWork(reason: LineageWorkUnavailableReason): UnavailableLineageWork {
  return { availability: 'unavailable', linkedWork: [], reason, recentCompletedWork: [] }
}

function compareRecentCompletedWork(left: RecentCompletedWork, right: RecentCompletedWork): number {
  const leftTime = completedTimestamp(left)
  const rightTime = completedTimestamp(right)
  return rightTime - leftTime
}

function completedTimestamp(work: RecentCompletedWork): number {
  if (work.completedAt === undefined) {
    return Number.NEGATIVE_INFINITY
  }
  const timestamp = Date.parse(work.completedAt)
  return Number.isNaN(timestamp) ? Number.NEGATIVE_INFINITY : timestamp
}

function normalizeWorkStatus(value: string | undefined): string {
  return readOptionalText(value)?.toLowerCase() ?? 'unknown'
}

function isCompletedStatus(value: string | undefined): boolean {
  return completedStatuses.has(normalizeWorkStatus(value))
}

function isWorkArtifactType(value: string): value is WorkArtifactType {
  return workArtifactTypes.includes(value as WorkArtifactType)
}

function isWorkArtifact(value: ArtifactLookupItem): value is WorkArtifact {
  return isWorkArtifactType(value.artifactType)
}

function workKindForArtifactType(artifactType: WorkArtifactType): WorkKind {
  return artifactType === 'TASK' ? 'task' : 'run'
}

function workArtifactTypeForKind(kind: WorkKind): WorkArtifactType {
  return kind === 'task' ? 'TASK' : 'RUN_RECORD'
}

function artifactIdentityKey(identity: ArtifactIdentity): string {
  return workIdentityKey(identity.artifactType, identity.artifactId)
}

function workIdentityKey(artifactType: DashboardArtifactType, artifactId: string): string {
  return `${artifactType}:${artifactId}`
}

function isExplicitArtifactStatus(value: string): value is ExplicitArtifactStatus {
  return value === 'approved'
    || value === 'draft'
    || value === 'pending'
    || value === 'unknown'
    || value === 'conflict'
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readOptionalText(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : undefined
}
