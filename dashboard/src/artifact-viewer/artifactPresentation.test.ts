import { describe, expect, it } from 'vitest'
import {
  isArtifactPresentationContent,
  isArtifactPresentationMetadata,
  isDocumentOutlineEntry,
  isPriorityDocument,
  isTaskGraphSummary,
  parseArtifactPresentationContent,
  parseArtifactPresentationMetadata,
} from './artifactPresentation'

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
})
