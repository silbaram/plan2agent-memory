import { describe, expect, it } from 'vitest'
import {
  deriveGateProgressItem,
  deriveLineageWork,
  deriveRecentCompletedWork,
} from './gateLineagePresentation'
import type { ArtifactDetail, ArtifactLookupItem, DashboardArtifactType } from '../data-access'

describe('Gate and lineage presentation models', () => {
  it('gives a valid approval audit precedence over an explicit draft', () => {
    const result = deriveGateProgressItem({
      approvalAudit: { approved_at: '2026-08-11T00:00:00Z', approved_by: 'owner' },
      artifact: detail(),
      explicitStatus: 'draft',
      gate: 'Gate B',
      isCurrent: true,
      label: 'Specification',
    })

    expect(result).toEqual({
      detail: 'Approval audit',
      explicitStatus: 'approved',
      gate: 'Gate B',
      isCurrent: true,
      label: 'Specification',
    })
  })

  it('uses explicit status, then presence, and never treats presence as approval', () => {
    expect(deriveGateProgressItem({
      artifact: detail(),
      explicitStatus: ' draft ',
      gate: 'Gate B',
      label: 'Specification',
    }).explicitStatus).toBe('draft')
    expect(deriveGateProgressItem({
      artifact: detail(),
      gate: 'Gate C',
      label: 'Task graph',
    })).toMatchObject({
      explicitStatus: 'pending',
      detail: 'Artifact available; approval is not recorded',
    })
    expect(deriveGateProgressItem({
      artifact: null,
      gate: 'Gate D',
      label: 'Review',
    })).toMatchObject({ explicitStatus: 'unknown', detail: 'Artifact unavailable' })
  })

  it('does not approve malformed audit evidence and exposes conflicts truthfully', () => {
    expect(deriveGateProgressItem({
      approvalAudit: { approved_by: 'owner' },
      artifact: detail(),
      explicitStatus: 'approved',
      gate: 'Gate B',
      label: 'Specification',
    })).toMatchObject({ explicitStatus: 'conflict' })
    expect(deriveGateProgressItem({
      approvalAudit: { approved_by: 'owner' },
      artifact: detail(),
      explicitStatus: 'draft',
      gate: 'Gate B',
      label: 'Specification',
    })).toMatchObject({ explicitStatus: 'draft' })
    expect(deriveGateProgressItem({
      approvalAudit: 'approved',
      artifact: detail(),
      gate: 'Gate B',
      label: 'Specification',
    })).toMatchObject({ explicitStatus: 'unknown' })
  })

  it('links only direct lineage and canonical task/run identifiers, never titles', () => {
    const selected = detail({
      lineage: {
        artifactRefs: [{ artifactId: 'task-direct-artifact', artifactType: 'TASK', sourcePath: null }],
        runId: 'run-lineage',
        taskId: null,
      },
      source: { sourceRunId: null, sourceTaskId: 'task-source' },
      title: 'Same title must not be a relation',
    })
    const result = deriveLineageWork({
      artifact: selected,
      workArtifacts: [
        lookup({ artifactId: 'task-direct-artifact', artifactType: 'TASK', taskId: 'unrelated-task', title: 'Direct task' }),
        lookup({ artifactId: 'task-source-artifact', artifactType: 'TASK', taskId: 'task-source', title: 'Source task' }),
        lookup({ artifactId: 'run-artifact', artifactType: 'RUN_RECORD', runId: 'run-lineage', title: 'Lineage run' }),
        lookup({ artifactId: 'same-title-only', artifactType: 'TASK', taskId: 'not-linked', title: 'Same title must not be a relation' }),
      ],
    })

    expect(result).toMatchObject({ availability: 'available' })
    if (result.availability === 'available') {
      expect(result.linkedWork.map((work) => [work.identity.artifactId, work.relation])).toEqual([
        ['task-direct-artifact', 'lineage_artifact_ref'],
        ['task-source-artifact', 'source_task_id'],
        ['run-artifact', 'lineage_run_id'],
      ])
      expect(result.linkedWork.map((work) => work.identity.artifactId)).not.toContain('same-title-only')
    }
  })

  it('returns explicit unavailable states for absent, dangling, and conflicting lineage', () => {
    expect(deriveLineageWork({ artifact: detail(), workArtifacts: [] })).toEqual({
      availability: 'unavailable',
      linkedWork: [],
      reason: 'missing_lineage',
      recentCompletedWork: [],
    })

    expect(deriveLineageWork({
      artifact: detail({ source: { sourceRunId: null, sourceTaskId: 'task-missing' } }),
      workArtifacts: [],
    })).toMatchObject({ availability: 'unavailable', reason: 'dangling_lineage' })

    expect(deriveLineageWork({
      artifact: detail({ source: { sourceRunId: null, sourceTaskId: 'task-duplicate' } }),
      workArtifacts: [
        lookup({ artifactId: 'task-first', artifactType: 'TASK', taskId: 'task-duplicate' }),
        lookup({ artifactId: 'task-second', artifactType: 'TASK', taskId: 'task-duplicate' }),
      ],
    })).toMatchObject({ availability: 'unavailable', reason: 'conflicting_lineage' })
  })

  it('derives recent completion only from linked work with explicit completed status', () => {
    const completedTask = lookup({
      artifactId: 'task-complete',
      artifactType: 'TASK',
      metadata: { completedAt: '2026-08-11T09:00:00Z', result: 'Tests passed', status: 'done' },
      taskId: 'task-complete',
      title: 'Completed task',
    })
    const completedRun = lookup({
      artifactId: 'run-complete',
      artifactType: 'RUN_RECORD',
      metadata: { completedAt: '2026-08-11T10:00:00Z', status: 'finished' },
      runId: 'run-complete',
      title: 'Completed run',
    })
    const activeTask = lookup({
      artifactId: 'task-active',
      artifactType: 'TASK',
      metadata: { status: 'in_progress' },
      taskId: 'task-active',
      title: 'Active task',
    })
    const result = deriveLineageWork({
      artifact: detail({
        lineage: { artifactRefs: [], runId: 'run-complete', taskId: 'task-complete' },
        source: { sourceRunId: null, sourceTaskId: 'task-active' },
      }),
      workArtifacts: [completedTask, completedRun, activeTask],
    })

    expect(result).toMatchObject({ availability: 'available' })
    if (result.availability === 'available') {
      expect(result.recentCompletedWork.map((entry) => entry.work.identity.artifactId)).toEqual(['run-complete', 'task-complete'])
      expect(result.recentCompletedWork[1]).toMatchObject({ result: 'Tests passed' })
      expect(result.recentCompletedWork.map((entry) => entry.work.identity.artifactId)).not.toContain('task-active')
      expect(deriveRecentCompletedWork(result.linkedWork, [completedTask, completedRun, activeTask]))
        .toEqual(result.recentCompletedWork)
    }
  })
})

function detail({
  lineage = { artifactRefs: [], runId: null, taskId: null },
  source = { sourceRunId: null, sourceTaskId: null },
  title = 'Product specification',
}: {
  readonly lineage?: Partial<Pick<ArtifactDetail['lineage'], 'artifactRefs' | 'runId' | 'taskId'>>
  readonly source?: Partial<Pick<ArtifactDetail['source'], 'sourceRunId' | 'sourceTaskId'>>
  readonly title?: string
} = {}): ArtifactDetail {
  return {
    artifactId: 'document-001',
    artifactType: 'DOCUMENT_SNAPSHOT',
    createdAt: null,
    iterationId: 'iteration-1',
    lineage: {
      artifactRefs: lineage.artifactRefs ?? [],
      contentHash: null,
      documentId: null,
      iterationId: 'iteration-1',
      projectId: 'project-1',
      runId: lineage.runId ?? null,
      snapshotVersion: null,
      taskGraphId: null,
      taskId: lineage.taskId ?? null,
    },
    mediaType: 'application/json',
    metadata: {},
    projectId: 'project-1',
    rawContent: '{}',
    source: {
      sourceDocumentId: null,
      sourceIterationId: null,
      sourcePath: null,
      sourceProjectId: null,
      sourceReference: null,
      sourceRunId: source.sourceRunId ?? null,
      sourceTaskGraphId: null,
      sourceTaskId: source.sourceTaskId ?? null,
    },
    title,
    updatedAt: null,
  }
}

function lookup({
  artifactId,
  artifactType,
  metadata = {},
  runId = null,
  taskId = null,
  title = artifactId,
}: {
  readonly artifactId: string
  readonly artifactType: DashboardArtifactType
  readonly metadata?: Readonly<Record<string, string>>
  readonly runId?: string | null
  readonly taskId?: string | null
  readonly title?: string
}): ArtifactLookupItem {
  return {
    artifactId,
    artifactType,
    contentHash: null,
    createdAt: null,
    iterationId: 'iteration-1',
    lineage: {
      contentHash: null,
      iterationId: 'iteration-1',
      projectId: 'project-1',
      runId,
      snapshotVersion: null,
      sourcePath: null,
      taskId,
    },
    metadata,
    projectId: 'project-1',
    runId,
    snapshotVersion: null,
    sourceIds: {
      sourceChunkId: null,
      sourceDocumentId: null,
      sourceIterationId: null,
      sourceProjectId: null,
      sourceRunId: null,
      sourceTaskGraphId: null,
      sourceTaskId: null,
    },
    sourcePath: null,
    sourceReference: null,
    taskId,
    title,
    updatedAt: null,
  }
}
