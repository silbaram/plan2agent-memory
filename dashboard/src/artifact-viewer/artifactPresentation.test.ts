import { describe, expect, it } from 'vitest'
import {
  classifyPriorityDocuments,
  isArtifactPresentationContent,
  isArtifactPresentationMetadata,
  isDocumentOutlineEntry,
  isPriorityDocument,
  isTaskGraphSummary,
  parseArtifactPresentationContent,
  parseArtifactPresentationMetadata,
} from './artifactPresentation'
import type { ArtifactLookupItem, DashboardArtifactType, Page } from '../data-access'

const identity = {
  artifactId: 'artifact-001',
  artifactType: 'DOCUMENT_SNAPSHOT',
}

const linkedWork = {
  identity: {
    artifactId: 'task-001',
    artifactType: 'TASK',
  },
  label: 'Presentation models',
  relation: 'derived_from',
  status: 'in_progress',
  taskId: 'task-001',
}

const priorityScope = {
  iterationId: 'iteration-1',
  projectId: 'project-1',
  sourceIterationId: 'v4-dashboard-refresh',
}

describe('artifact presentation models', () => {
  it('accepts complete raw content and metadata for every readonly presentation model', () => {
    const content = {
      documentOutline: [{ fragmentId: 'goals', headingLevel: 2, label: 'Goals' }],
      documentSummary: {
        purpose: 'Describe the approved dashboard work',
        summary: 'A bounded presentation model contract.',
        title: 'Dashboard refresh',
      },
      gateProgress: [{ detail: 'Approved specification', explicitStatus: 'approved', gate: 'Gate B', isCurrent: true, label: 'Specification' }],
      priorityDocuments: [{
        canonicalKind: 'product_spec',
        explicitStatus: 'approved',
        gate: 'Gate B',
        identity,
        label: 'Product specification',
        linkedWorkCount: 2,
        purpose: 'Defines the active iteration outcome',
        sourcePath: 'gate-b-spec/product-spec.md',
      }],
      recentCompletedWork: [{
        completedAt: '2026-08-10T12:00:00Z',
        result: 'Focused tests passed',
        work: linkedWork,
      }],
      taskGraphSummary: {
        currentTaskId: 'task-001',
        dependencyCount: 3,
        sourceSpecRefs: ['implementation.interfaces'],
        statusCounts: [{ count: 1, status: 'in_progress' }, { count: 2, status: 'todo' }],
        taskCount: 3,
      },
    }
    const metadata = { linkedWork: [linkedWork] }

    expect(parseArtifactPresentationContent(JSON.stringify(content))).toEqual(content)
    expect(parseArtifactPresentationMetadata(metadata)).toEqual(metadata)
  })

  it('accepts models when optional fields are missing', () => {
    expect(isArtifactPresentationContent({
      gateProgress: [{ explicitStatus: 'pending', gate: 'Gate C', label: 'Task graph' }],
      recentCompletedWork: [{ work: linkedWork }],
      taskGraphSummary: {
        dependencyCount: 0,
        sourceSpecRefs: [],
        statusCounts: [],
        taskCount: 0,
      },
    })).toBe(true)
    expect(isArtifactPresentationMetadata({})).toBe(true)
    expect(isDocumentOutlineEntry({ fragmentId: 'scope', headingLevel: 2, label: 'Scope' })).toBe(true)
  })

  it('rejects wrong primitive types at raw-content and metadata boundaries', () => {
    expect(isPriorityDocument({
      canonicalKind: 'product_spec',
      explicitStatus: 'approved',
      gate: 'Gate B',
      identity,
      label: 'Product specification',
      linkedWorkCount: '2',
      purpose: 'Defines the active iteration outcome',
      sourcePath: 'gate-b-spec/product-spec.md',
    })).toBe(false)
    expect(isTaskGraphSummary({
      dependencyCount: 1,
      sourceSpecRefs: [],
      statusCounts: [],
      taskCount: '3',
    })).toBe(false)
    expect(isArtifactPresentationContent({ gateProgress: 'approved' })).toBe(false)
    expect(isArtifactPresentationMetadata({ linkedWork: 'task-001' })).toBe(false)
    expect(parseArtifactPresentationContent('{"taskGraphSummary":false}')).toBeNull()
    expect(parseArtifactPresentationContent(42)).toBeNull()
  })

  it('rejects arrays with invalid members', () => {
    expect(isArtifactPresentationContent({
      priorityDocuments: [{
        canonicalKind: 'product_spec',
        explicitStatus: 'approved',
        gate: 'Gate B',
        identity,
        label: 'Product specification',
        linkedWorkCount: 0,
        purpose: 'Defines the active iteration outcome',
        sourcePath: 'gate-b-spec/product-spec.md',
      }, {
        canonicalKind: 'unknown_document',
        explicitStatus: 'approved',
        gate: 'Gate B',
        identity,
        label: 'Unknown',
        linkedWorkCount: 0,
        purpose: 'Invalid canonical kind',
        sourcePath: 'unknown.md',
      }],
    })).toBe(false)
    expect(isArtifactPresentationContent({
      recentCompletedWork: [{ work: linkedWork }, { completedAt: 42, work: linkedWork }],
    })).toBe(false)
    expect(isArtifactPresentationMetadata({
      linkedWork: [linkedWork, { label: 'Missing identity', relation: 'derived_from', status: 'todo' }],
    })).toBe(false)
  })

  it('classifies only exact normalized source paths and allowed artifact types', () => {
    const similarProductPath = artifactLookup({
      artifactId: 'similar-product-path',
      artifactType: 'DOCUMENT_SNAPSHOT',
      sourcePath: 'iterations/v4-dashboard-refresh/gate-b-spec/product-spec.md.bak',
      title: 'Product specification',
    })
    const wrongType = artifactLookup({
      artifactId: 'wrong-product-type',
      artifactType: 'TASK_GRAPH',
      sourcePath: 'iterations/v4-dashboard-refresh/gate-b-spec/product-spec.md',
      title: 'Product specification',
    })

    const classification = classifyPriorityDocuments({
      lookup: [artifactPage([similarProductPath, wrongType])],
      scope: priorityScope,
    })

    expect(classification.priorityDocuments).toEqual([])
    expect(classification.selectedPriorityDocument).toBeNull()
    expect(classification.supportingDocuments).toEqual([similarProductPath, wrongType])
  })

  it('keeps absent priority documents absent', () => {
    const unrelatedArtifact = artifactLookup({
      artifactId: 'unrelated-document',
      artifactType: 'DOCUMENT_SNAPSHOT',
      sourcePath: 'iterations/v4-dashboard-refresh/gate-a-intake/intake.md',
      title: 'Intake',
    })

    const classification = classifyPriorityDocuments({
      lookup: [artifactPage([unrelatedArtifact])],
      scope: priorityScope,
    })

    expect(classification.priorityDocuments).toEqual([])
    expect(classification.supportingDocuments).toEqual([unrelatedArtifact])
  })

  it('uses the first server snapshot for a kind while keeping priority order fixed', () => {
    const taskGraph = artifactLookup({
      artifactId: 'task-graph',
      artifactType: 'TASK_GRAPH',
      sourcePath: 'iterations/v4-dashboard-refresh/gate-c-task-graph/task-graph.json',
      title: 'A title that is not used',
    })
    const firstProductSnapshot = artifactLookup({
      artifactId: 'product-snapshot-first',
      artifactType: 'DOCUMENT_SNAPSHOT',
      sourcePath: './iterations/v4-dashboard-refresh/gate-b-spec/product-spec.md',
      title: 'Another unrelated title',
    })
    const secondProductSnapshot = artifactLookup({
      artifactId: 'product-snapshot-second',
      artifactType: 'DOCUMENT_SNAPSHOT',
      sourcePath: 'iterations/v4-dashboard-refresh/gate-b-spec/product-spec.md',
      title: 'Product specification',
    })

    const classification = classifyPriorityDocuments({
      lookup: [artifactPage([taskGraph]), artifactPage([firstProductSnapshot, secondProductSnapshot])],
      scope: priorityScope,
    })

    expect(classification.priorityDocuments.map((document) => document.canonicalKind)).toEqual(['product_spec', 'task_graph'])
    expect(classification.priorityDocuments.map((document) => document.identity.artifactId)).toEqual([
      'product-snapshot-first',
      'task-graph',
    ])
    expect(classification.supportingDocuments).toEqual([secondProductSnapshot])
  })

  it('deduplicates a selected artifact across lookup and current page before selection', () => {
    const firstProductSnapshot = artifactLookup({
      artifactId: 'product-snapshot-first',
      artifactType: 'DOCUMENT_SNAPSHOT',
      sourcePath: 'iterations/v4-dashboard-refresh/gate-b-spec/product-spec.md',
      title: 'Product specification',
    })
    const selectedProductSnapshot = artifactLookup({
      artifactId: 'product-snapshot-selected',
      artifactType: 'DOCUMENT_SNAPSHOT',
      sourcePath: 'iterations/v4-dashboard-refresh/gate-b-spec/product-spec.md',
      title: 'Product specification duplicate',
    })

    const classification = classifyPriorityDocuments({
      currentPage: artifactPage([firstProductSnapshot, selectedProductSnapshot]),
      lookup: [artifactPage([firstProductSnapshot])],
      scope: priorityScope,
      selection: {
        artifactId: selectedProductSnapshot.artifactId,
        artifactType: 'DOCUMENT_SNAPSHOT',
      },
    })

    expect(classification.priorityDocuments).toHaveLength(1)
    expect(classification.priorityDocuments[0]?.identity.artifactId).toBe('product-snapshot-selected')
    expect(classification.selectedPriorityDocument?.identity.artifactId).toBe('product-snapshot-selected')
    expect(classification.supportingDocuments).toEqual([firstProductSnapshot])
  })
})

function artifactPage(items: readonly ArtifactLookupItem[]): Page<ArtifactLookupItem> {
  return { items, nextCursor: null }
}

function artifactLookup({
  artifactId,
  artifactType,
  sourcePath,
  title,
}: {
  readonly artifactId: string
  readonly artifactType: DashboardArtifactType
  readonly sourcePath: string
  readonly title: string
}): ArtifactLookupItem {
  return {
    artifactId,
    artifactType,
    contentHash: null,
    createdAt: null,
    iterationId: priorityScope.iterationId,
    lineage: {
      contentHash: null,
      iterationId: priorityScope.iterationId,
      projectId: priorityScope.projectId,
      runId: null,
      snapshotVersion: null,
      sourcePath,
      taskId: null,
    },
    metadata: {},
    projectId: priorityScope.projectId,
    runId: null,
    snapshotVersion: null,
    sourceIds: {
      sourceChunkId: null,
      sourceDocumentId: null,
      sourceIterationId: priorityScope.sourceIterationId,
      sourceProjectId: null,
      sourceRunId: null,
      sourceTaskGraphId: null,
      sourceTaskId: null,
    },
    sourcePath,
    sourceReference: null,
    taskId: null,
    title,
    updatedAt: null,
  }
}
