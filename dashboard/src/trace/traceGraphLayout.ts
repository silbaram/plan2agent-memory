import type { Edge, Node } from '@xyflow/react'
import type { DashboardArtifactType, GraphEdge, GraphNode, GraphTrace, GraphTraceNode } from '../data-access'

const DEPTH_COLUMN_WIDTH = 280
const DEPTH_ROW_HEIGHT = 156

interface TraceFlowNodeData extends Record<string, unknown> {
  readonly artifactHref: string | null
  readonly depth: number
  readonly hierarchy: TraceNodeHierarchy
  readonly label: string
  readonly nodeId: string
  readonly nodeKind: string
}

interface TraceFlowEdgeData extends Record<string, unknown> {
  readonly edgeType: string
  readonly hierarchy: TraceEdgeHierarchy
}

export type TraceFlowNode = Node<TraceFlowNodeData, 'default'>
export type TraceFlowEdge = Edge<TraceFlowEdgeData, 'default'>

export interface TraceNodeEntry {
  readonly depth: number
  readonly node: GraphNode
}

export interface TraceEdgeEntry {
  readonly edge: GraphEdge
  readonly hierarchy: TraceEdgeHierarchy
  readonly isDangling: boolean
}

export type TraceEdgeHierarchy = 'primary' | 'secondary'

export type TraceNodeHierarchy = 'primary' | 'secondary' | 'unrelated'

export interface TraceGraphLayout {
  readonly danglingEdges: readonly GraphEdge[]
  readonly edgeEntries: readonly TraceEdgeEntry[]
  readonly flowEdges: TraceFlowEdge[]
  readonly flowNodes: TraceFlowNode[]
  readonly nodeEntries: readonly TraceNodeEntry[]
}

export function layoutTraceGraph(trace: GraphTrace): TraceGraphLayout {
  const nodeEntries = traceNodeEntries(trace)
  const nodeIds = new Set(nodeEntries.map((entry) => entry.node.nodeId))
  const edgeEntries = [...trace.edges]
    .sort(compareGraphEdges)
    .map((edge) => ({
      edge,
      hierarchy: traceEdgeHierarchy(edge),
      isDangling: !nodeIds.has(edge.fromNodeId) || !nodeIds.has(edge.toNodeId),
    }))
  const danglingEdges = edgeEntries.filter((entry) => entry.isDangling).map((entry) => entry.edge)
  const nodeHierarchyById = traceNodeHierarchyById(nodeEntries, edgeEntries)
  const positionedEntries = positionTraceNodes(nodeEntries)
  const flowNodes = positionedEntries.map(({ entry, position }) => ({
    ariaLabel: `${entry.node.nodeKind} ${entry.node.label}, node ID ${entry.node.nodeId}, depth ${entry.depth}`,
    className: `trace-flow__node trace-flow__node--${nodeHierarchyById.get(entry.node.nodeId) ?? 'unrelated'}`,
    data: {
      artifactHref: supportedArtifactHref(entry.node),
      depth: entry.depth,
      hierarchy: nodeHierarchyById.get(entry.node.nodeId) ?? 'unrelated',
      label: `${entry.node.label} (${entry.node.nodeKind}, depth ${entry.depth})`,
      nodeId: entry.node.nodeId,
      nodeKind: entry.node.nodeKind,
    },
    id: entry.node.nodeId,
    position,
    type: 'default' as const,
  }))
  const flowEdges = edgeEntries
    .filter((entry) => !entry.isDangling)
    .map(({ edge, hierarchy }) => ({
      ariaLabel: `${edge.edgeType}: ${edge.fromNodeId}에서 ${edge.toNodeId}`,
      className: `trace-flow__edge trace-flow__edge--${hierarchy}`,
      data: { edgeType: edge.edgeType, hierarchy },
      id: edge.edgeId,
      label: edge.edgeType,
      reconnectable: false,
      selectable: false,
      source: edge.fromNodeId,
      target: edge.toNodeId,
      type: 'default' as const,
    }))

  return { danglingEdges, edgeEntries, flowEdges, flowNodes, nodeEntries }
}

export function traceEdgeHierarchy(edge: GraphEdge): TraceEdgeHierarchy {
  // 주 계보는 API가 명시한 파생 또는 실행 관계에서만 표시한다. node kind, title, depth로 관계를 보완하지 않는다.
  return edge.edgeType === 'DERIVED_FROM' || edge.edgeType === 'EXECUTED_FOR'
    ? 'primary'
    : 'secondary'
}

export function supportedArtifactHref(node: GraphNode) {
  const artifact = artifactForGraphNode(node)
  return artifact === null ? null : `/artifact/${artifact.artifactType}/${encodeURIComponent(artifact.artifactId)}`
}

function traceNodeEntries(trace: GraphTrace): readonly TraceNodeEntry[] {
  const entries: TraceNodeEntry[] = []
  const seenNodeIds = new Set<string>()
  const traceNodes = [...trace.nodes].sort(compareTraceNodes)
  const rootTraceNode = traceNodes.find((entry) => entry.node.nodeId === trace.root.nodeId)
  if (rootTraceNode === undefined) {
    entries.push({ depth: 0, node: trace.root })
    seenNodeIds.add(trace.root.nodeId)
  }
  for (const entry of traceNodes) {
    if (!seenNodeIds.has(entry.node.nodeId)) {
      entries.push(entry)
      seenNodeIds.add(entry.node.nodeId)
    }
  }
  return entries.sort(compareTraceNodes)
}

function positionTraceNodes(entries: readonly TraceNodeEntry[]) {
  const nodesAtDepth = new Map<number, number>()
  return entries.map((entry) => {
    const positionAtDepth = nodesAtDepth.get(entry.depth) ?? 0
    nodesAtDepth.set(entry.depth, positionAtDepth + 1)
    return {
      entry,
      position: {
        x: entry.depth * DEPTH_COLUMN_WIDTH,
        y: positionAtDepth * DEPTH_ROW_HEIGHT,
      },
    }
  })
}

function traceNodeHierarchyById(
  nodeEntries: readonly TraceNodeEntry[],
  edgeEntries: readonly TraceEdgeEntry[],
): ReadonlyMap<string, TraceNodeHierarchy> {
  const hierarchyById = new Map(nodeEntries.map((entry) => [entry.node.nodeId, 'unrelated'] as const))
  for (const entry of edgeEntries) {
    if (entry.isDangling) {
      continue
    }
    updateNodeHierarchy(hierarchyById, entry.edge.fromNodeId, entry.hierarchy)
    updateNodeHierarchy(hierarchyById, entry.edge.toNodeId, entry.hierarchy)
  }
  return hierarchyById
}

function updateNodeHierarchy(
  hierarchyById: Map<string, TraceNodeHierarchy>,
  nodeId: string,
  hierarchy: TraceEdgeHierarchy,
): void {
  const currentHierarchy = hierarchyById.get(nodeId)
  if (currentHierarchy === undefined || currentHierarchy === 'primary') {
    return
  }
  hierarchyById.set(nodeId, hierarchy)
}

function artifactForGraphNode(node: GraphNode): { readonly artifactId: string; readonly artifactType: DashboardArtifactType } | null {
  switch (node.nodeKind) {
    case 'DOCUMENT':
      return node.documentId === null ? null : { artifactId: node.documentId, artifactType: 'DOCUMENT_SNAPSHOT' }
    case 'TASK':
      return node.taskId === null ? null : { artifactId: node.taskId, artifactType: 'TASK' }
    case 'RUN':
      return node.runId === null ? null : { artifactId: node.runId, artifactType: 'RUN_RECORD' }
    default:
      return null
  }
}

function compareTraceNodes(left: GraphTraceNode, right: GraphTraceNode) {
  return left.depth - right.depth || left.node.nodeId.localeCompare(right.node.nodeId)
}

function compareGraphEdges(left: GraphEdge, right: GraphEdge) {
  return left.edgeId.localeCompare(right.edgeId) || left.fromNodeId.localeCompare(right.fromNodeId) || left.toNodeId.localeCompare(right.toNodeId)
}
