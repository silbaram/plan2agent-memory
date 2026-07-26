import type { Edge, Node } from '@xyflow/react'
import type { DashboardArtifactType, GraphEdge, GraphNode, GraphTrace, GraphTraceNode } from '../data-access'

const DEPTH_COLUMN_WIDTH = 280
const DEPTH_ROW_HEIGHT = 156

interface TraceFlowNodeData extends Record<string, unknown> {
  readonly artifactHref: string | null
  readonly depth: number
  readonly label: string
  readonly nodeId: string
  readonly nodeKind: string
}

interface TraceFlowEdgeData extends Record<string, unknown> {
  readonly edgeType: string
}

export type TraceFlowNode = Node<TraceFlowNodeData, 'default'>
export type TraceFlowEdge = Edge<TraceFlowEdgeData, 'default'>

export interface TraceNodeEntry {
  readonly depth: number
  readonly node: GraphNode
}

export interface TraceEdgeEntry {
  readonly edge: GraphEdge
  readonly isDangling: boolean
}

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
    .map((edge) => ({ edge, isDangling: !nodeIds.has(edge.fromNodeId) || !nodeIds.has(edge.toNodeId) }))
  const danglingEdges = edgeEntries.filter((entry) => entry.isDangling).map((entry) => entry.edge)
  const positionedEntries = positionTraceNodes(nodeEntries)
  const flowNodes = positionedEntries.map(({ entry, position }) => ({
    ariaLabel: `${entry.node.nodeKind} ${entry.node.label}, node ID ${entry.node.nodeId}, depth ${entry.depth}`,
    data: {
      artifactHref: supportedArtifactHref(entry.node),
      depth: entry.depth,
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
    .map(({ edge }) => ({
      ariaLabel: `${edge.edgeType}: ${edge.fromNodeId}에서 ${edge.toNodeId}`,
      data: { edgeType: edge.edgeType },
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
