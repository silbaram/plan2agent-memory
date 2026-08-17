import type { GraphNode, GraphTrace } from '../data-access'
import type { TraceEdgeEntry, TraceGraphLayout } from './traceGraphLayout'

type CompletionUnavailableReason = 'missing_canonical_lineage' | 'no_completed_task'

export interface CompletedTraceTask {
  readonly completedAt: string | null
  readonly content: string | null
  readonly edgeTypes: readonly string[]
  readonly label: string
  readonly result: string | null
  readonly taskId: string
}

export interface AvailableTraceCompletionContext {
  readonly availability: 'available'
  readonly completedTasks: readonly CompletedTraceTask[]
}

export interface UnavailableTraceCompletionContext {
  readonly availability: 'unavailable'
  readonly completedTasks: readonly []
  readonly reason: CompletionUnavailableReason
}

export type TraceCompletionContext = AvailableTraceCompletionContext | UnavailableTraceCompletionContext

export interface TracePresentation {
  readonly completionContext: TraceCompletionContext
  readonly isEmpty: boolean
  readonly primaryEdgeCount: number
  readonly secondaryEdgeCount: number
}

const completedStatuses = new Set(['completed', 'done', 'finished'])

export function deriveTracePresentation(trace: GraphTrace, layout: TraceGraphLayout): TracePresentation {
  const primaryEdgeCount = layout.edgeEntries.filter((entry) => entry.hierarchy === 'primary' && !entry.isDangling).length
  const secondaryEdgeCount = layout.edgeEntries.filter((entry) => entry.hierarchy === 'secondary' && !entry.isDangling).length

  return {
    completionContext: deriveTraceCompletionContext(trace, layout.edgeEntries),
    isEmpty: isTraceEmpty(trace, layout),
    primaryEdgeCount,
    secondaryEdgeCount,
  }
}

export function deriveTraceCompletionContext(
  trace: GraphTrace,
  edgeEntries: readonly TraceEdgeEntry[],
): TraceCompletionContext {
  const traceNodes = nodesById(trace)
  // taskId와 반환된 primary lineage edge가 함께 있어야만 graph의 canonical 관계에서 완료 맥락을 읽는다. label, depth, 보조 관계는 근거가 아니다.
  const canonicalTaskEdges = edgeEntries.filter((entry) => !entry.isDangling && entry.hierarchy === 'primary')
  const canonicalTaskNodeIds = new Set(
    canonicalTaskEdges.flatMap((entry) => [entry.edge.fromNodeId, entry.edge.toNodeId]),
  )
  const canonicalTasks = [...canonicalTaskNodeIds]
    .map((nodeId) => traceNodes.get(nodeId))
    .filter(isCanonicalTaskNode)

  if (canonicalTasks.length === 0) {
    return unavailableCompletionContext('missing_canonical_lineage')
  }

  const completedTasks = canonicalTasks
    .filter((node) => isCompletedStatus(node.metadata.status))
    .map((node) => completedTraceTask(node, canonicalTaskEdges))
    .sort(compareCompletedTraceTasks)

  return completedTasks.length === 0
    ? unavailableCompletionContext('no_completed_task')
    : { availability: 'available', completedTasks }
}

function isTraceEmpty(trace: GraphTrace, layout: TraceGraphLayout): boolean {
  const hasRelatedNode = layout.nodeEntries.some((entry) => entry.node.nodeId !== trace.root.nodeId)
  const hasConnectedEdge = layout.edgeEntries.some((entry) => !entry.isDangling)
  return !hasRelatedNode && !hasConnectedEdge
}

function nodesById(trace: GraphTrace): ReadonlyMap<string, GraphNode> {
  const nodes = new Map<string, GraphNode>()
  nodes.set(trace.root.nodeId, trace.root)
  for (const entry of trace.nodes) {
    if (!nodes.has(entry.node.nodeId)) {
      nodes.set(entry.node.nodeId, entry.node)
    }
  }
  return nodes
}

function isCanonicalTaskNode(node: GraphNode | undefined): node is GraphNode & { readonly taskId: string } {
  return node !== undefined && node.nodeKind === 'TASK' && nonBlankText(node.taskId) !== null
}

function completedTraceTask(node: GraphNode & { readonly taskId: string }, canonicalTaskEdges: readonly TraceEdgeEntry[]): CompletedTraceTask {
  const edgeTypes = canonicalTaskEdges
    .filter((entry) => entry.edge.fromNodeId === node.nodeId || entry.edge.toNodeId === node.nodeId)
    .map((entry) => entry.edge.edgeType)
    .filter((edgeType, index, allEdgeTypes) => allEdgeTypes.indexOf(edgeType) === index)
    .sort((left, right) => left.localeCompare(right))

  return {
    completedAt: nonBlankText(node.metadata.completedAt),
    content: nonBlankText(node.content),
    edgeTypes,
    label: node.label,
    result: nonBlankText(node.metadata.result),
    taskId: node.taskId,
  }
}

function compareCompletedTraceTasks(left: CompletedTraceTask, right: CompletedTraceTask): number {
  if (left.completedAt !== null && right.completedAt !== null) {
    const timestampOrder = right.completedAt.localeCompare(left.completedAt)
    if (timestampOrder !== 0) {
      return timestampOrder
    }
  }
  if (left.completedAt !== null) {
    return -1
  }
  if (right.completedAt !== null) {
    return 1
  }
  return left.taskId.localeCompare(right.taskId)
}

function isCompletedStatus(status: string | undefined): boolean {
  return completedStatuses.has(status?.trim().toLowerCase() ?? '')
}

function nonBlankText(value: string | null | undefined): string | null {
  const normalized = value?.trim()
  return normalized === undefined || normalized.length === 0 ? null : normalized
}

function unavailableCompletionContext(reason: CompletionUnavailableReason): UnavailableTraceCompletionContext {
  return { availability: 'unavailable', completedTasks: [], reason }
}
