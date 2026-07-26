import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { BrowserRouter } from 'react-router-dom'
import type { DashboardApiClient, GraphNode, GraphTrace, GraphTraceRequest } from '../data-access'
import { TraceRoute } from './TraceRoute'
import { layoutTraceGraph } from './traceGraphLayout'
import { DEFAULT_TRACE_DIRECTION, DEFAULT_TRACE_MAX_DEPTH, parseTraceUrl, serializeTraceUrl } from './traceUrlState'

const projectId = '11111111-1111-4111-8111-111111111111'
const iterationId = '22222222-2222-4222-8222-222222222222'

afterEach(() => {
  cleanup()
  window.history.replaceState({}, '', '/')
})

describe('trace URL state', () => {
  it('recovers invalid root, direction, and depth parameters without issuing a trace request', () => {
    const state = parseTraceUrl(new URLSearchParams('projectId=invalid%2Fproject&naturalKey=task%2Funsafe&direction=SIDEWAYS&maxDepth=11'))

    expect(state.projectId).toBeNull()
    expect(state.naturalKey).toBeNull()
    expect(state.direction).toBe(DEFAULT_TRACE_DIRECTION)
    expect(state.maxDepth).toBe(DEFAULT_TRACE_MAX_DEPTH)
    expect(state.invalidParameters).toContain('projectId 형식이 올바르지 않습니다')
    expect(state.invalidParameters).toContain('naturalKey 형식이 올바르지 않습니다')
    expect(serializeTraceUrl(state)).toBe('')
  })

  it('accepts BFF-safe project and iteration identifiers that are not UUIDs', () => {
    const state = parseTraceUrl(new URLSearchParams('projectId=p2a-local-artifact-store&iterationId=v3-owner-dashboard&naturalKey=task%3Atask-1'))

    expect(state).toEqual({
      direction: 'BOTH',
      invalidParameters: [],
      iterationId: 'v3-owner-dashboard',
      maxDepth: 3,
      naturalKey: 'task:task-1',
      projectId: 'p2a-local-artifact-store',
    })
    expect(serializeTraceUrl(state)).toContain('projectId=p2a-local-artifact-store')
  })
})

describe('trace graph layout', () => {
  it('uses stable API node and edge IDs in a deterministic depth grid while separating dangling edges', () => {
    const layout = layoutTraceGraph(traceFixture())

    expect(layout.flowNodes.map((node) => ({ id: node.id, position: node.position }))).toEqual([
      { id: 'node-task', position: { x: 0, y: 0 } },
      { id: 'node-document', position: { x: 280, y: 0 } },
      { id: 'node-run', position: { x: 280, y: 156 } },
    ])
    expect(layout.flowEdges.map((edge) => ({ id: edge.id, source: edge.source, target: edge.target }))).toEqual([
      { id: 'edge-task-document', source: 'node-task', target: 'node-document' },
      { id: 'edge-task-run', source: 'node-task', target: 'node-run' },
    ])
    expect(layout.danglingEdges.map((edge) => edge.edgeId)).toEqual(['edge-dangling'])
  })
})

describe('TraceRoute', () => {
  it('uses a selected API root to request a default bounded trace and exposes safe artifact links in the accessible list', async () => {
    const traceRequests: GraphTraceRequest[] = []
    const client = createClient({
      nodes: [documentNode(), taskNode(), runNode()],
      trace: async (request) => {
        traceRequests.push(request)
        return traceFixture()
      },
    })
    setLocation('/trace')

    renderTrace(client)

    fireEvent.change(await screen.findByLabelText('추적 시작 노드'), { target: { value: 'node-task' } })

    expect(await screen.findByText('노드 ID: node-task')).toBeTruthy()
    expect(traceRequests).toEqual([{
      direction: 'BOTH',
      iterationId,
      maxDepth: 3,
      naturalKey: 'task:task-1',
      projectId,
    }])
    expect(screen.getByRole('link', { name: 'Task one 산출물 열기' }).getAttribute('href')).toBe('/artifact/TASK/task-1')
    expect(screen.getByRole('link', { name: 'Document one 산출물 열기' }).getAttribute('href')).toBe('/artifact/DOCUMENT_SNAPSHOT/document-1')
    expect(screen.getByRole('link', { name: 'Run one 산출물 열기' }).getAttribute('href')).toBe('/artifact/RUN_RECORD/run-1')
    expect(window.location.search).toContain('direction=BOTH')
    expect(window.location.search).toContain('maxDepth=3')
  })

  it('keeps hyphenated project and iteration IDs when a selected API root starts a trace', async () => {
    const sourceProjectId = 'p2a-local-artifact-store'
    const sourceIterationId = 'v3-owner-dashboard'
    const sourceRoot = graphNode({
      iterationId: sourceIterationId,
      label: 'Owner dashboard task',
      naturalKey: 'task:task-020',
      nodeId: 'node-owner-task',
      nodeKind: 'TASK',
      projectId: sourceProjectId,
      taskId: 'task-020',
    })
    const traceRequests: GraphTraceRequest[] = []
    setLocation('/trace')
    renderTrace(createClient({
      nodes: [sourceRoot],
      trace: async (request) => {
        traceRequests.push(request)
        return {
          edges: [],
          nodes: [{ depth: 0, node: sourceRoot }],
          root: sourceRoot,
          truncated: false,
        }
      },
    }))

    fireEvent.change(await screen.findByLabelText('추적 시작 노드'), { target: { value: 'node-owner-task' } })

    expect(await screen.findByText('노드 ID: node-owner-task')).toBeTruthy()
    expect(traceRequests).toEqual([{
      direction: 'BOTH',
      iterationId: sourceIterationId,
      maxDepth: 3,
      naturalKey: 'task:task-020',
      projectId: sourceProjectId,
    }])
    expect(window.location.search).toContain('projectId=p2a-local-artifact-store')
    expect(window.location.search).toContain('iterationId=v3-owner-dashboard')
  })

  it('shows an empty initial state when the graph node endpoint has no root candidates', async () => {
    setLocation('/trace')
    renderTrace(createClient({ nodes: [], trace: async () => traceFixture() }))

    expect(await screen.findByRole('region', { name: '추적할 노드가 없습니다' })).toBeTruthy()
  })

  it('recovers an invalid URL visibly and does not send its unsafe values to traceGraph', async () => {
    let traceRequestCount = 0
    setLocation('/trace?projectId=invalid%2Fproject&naturalKey=task%2Funsafe&direction=OTHER&maxDepth=40')
    renderTrace(createClient({
      nodes: [taskNode()],
      trace: async () => {
        traceRequestCount += 1
        return traceFixture()
      },
    }))

    expect((await screen.findByRole('alert')).textContent).toContain('잘못된 trace URL을 복구했습니다')
    await waitFor(() => {
      expect(window.location.search).toBe('')
    })
    expect(traceRequestCount).toBe(0)
  })

  it('shows truncation and dangling edges without crashing the canvas and preserves the dangling edge in the accessible list', async () => {
    setLocation(tracePath())
    renderTrace(createClient({
      nodes: [taskNode()],
      trace: async () => ({ ...traceFixture(), truncated: true }),
    }))

    expect(await screen.findByText('일부 계보만 표시합니다')).toBeTruthy()
    expect(screen.getByRole('status', { name: '연결할 수 없는 간선' }).textContent).toContain('1개의 간선')
    expect(screen.getByText('간선 ID: edge-dangling')).toBeTruthy()
    expect(screen.getByText('이 간선은 응답에 없는 노드를 참조합니다.')).toBeTruthy()
  })

  it('shows a safe retryable error when the trace API fails', async () => {
    setLocation(tracePath())
    renderTrace(createClient({
      nodes: [taskNode()],
      trace: async () => {
        throw new Error('private upstream detail')
      },
    }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('계보를 표시할 수 없습니다')
    expect(alert.textContent).not.toContain('private upstream detail')
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy()
  })
})

function renderTrace(apiClient: DashboardApiClient) {
  return render(
    <BrowserRouter>
      <TraceRoute apiClient={apiClient} />
    </BrowserRouter>,
  )
}

function setLocation(pathname: string) {
  window.history.replaceState({}, '', pathname)
}

function tracePath() {
  return `/trace?projectId=${projectId}&iterationId=${iterationId}&naturalKey=task%3Atask-1&direction=BOTH&maxDepth=3`
}

function createClient({ nodes, trace }: { readonly nodes: readonly GraphNode[]; readonly trace: (request: GraphTraceRequest) => Promise<GraphTrace> }): DashboardApiClient {
  return {
    getArtifact: async () => {
      throw new Error('not used')
    },
    health: async () => {
      throw new Error('not used')
    },
    hybridSearch: async () => {
      throw new Error('not used')
    },
    keywordSearch: async () => {
      throw new Error('not used')
    },
    listArtifacts: async () => {
      throw new Error('not used')
    },
    listGraphNodes: async () => nodes,
    listProjectIterations: async () => {
      throw new Error('not used')
    },
    listProjects: async () => {
      throw new Error('not used')
    },
    semanticSearch: async () => {
      throw new Error('not used')
    },
    traceGraph: trace,
  }
}

function taskNode(): GraphNode {
  return graphNode({
    label: 'Task one',
    naturalKey: 'task:task-1',
    nodeId: 'node-task',
    nodeKind: 'TASK',
    taskId: 'task-1',
  })
}

function documentNode(): GraphNode {
  return graphNode({
    documentId: 'document-1',
    label: 'Document one',
    naturalKey: 'document:document-1',
    nodeId: 'node-document',
    nodeKind: 'DOCUMENT',
  })
}

function runNode(): GraphNode {
  return graphNode({
    label: 'Run one',
    naturalKey: 'run:run-1',
    nodeId: 'node-run',
    nodeKind: 'RUN',
    runId: 'run-1',
  })
}

function graphNode(overrides: Partial<GraphNode>): GraphNode {
  return {
    content: null,
    documentId: null,
    iterationId,
    label: 'Graph node',
    metadata: {},
    naturalKey: 'node:one',
    nodeId: 'node-one',
    nodeKind: 'EVIDENCE',
    projectId,
    runId: null,
    taskId: null,
    ...overrides,
  }
}

function traceFixture(): GraphTrace {
  return {
    edges: [
      {
        edgeId: 'edge-task-run',
        edgeType: 'AFFECTS',
        fromNodeId: 'node-task',
        metadata: {},
        projectId,
        sourceReference: null,
        toNodeId: 'node-run',
      },
      {
        edgeId: 'edge-dangling',
        edgeType: 'SUPPORTS',
        fromNodeId: 'node-run',
        metadata: {},
        projectId,
        sourceReference: null,
        toNodeId: 'node-missing',
      },
      {
        edgeId: 'edge-task-document',
        edgeType: 'DERIVED_FROM',
        fromNodeId: 'node-task',
        metadata: {},
        projectId,
        sourceReference: null,
        toNodeId: 'node-document',
      },
    ],
    nodes: [
      { depth: 1, node: runNode() },
      { depth: 0, node: taskNode() },
      { depth: 1, node: documentNode() },
    ],
    root: taskNode(),
    truncated: false,
  }
}
