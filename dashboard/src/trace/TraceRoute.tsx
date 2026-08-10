import { Background, ReactFlow } from '@xyflow/react'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type ChangeEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import {
  createDashboardQueries,
  dashboardApi,
  type DashboardApiClient,
  type GraphNode,
  type GraphTrace,
  type GraphTraceDirection,
  type GraphTraceRequest,
} from '../data-access'
import { DegradedState, EmptyStatePanel, ErrorState, LoadingState } from '../dashboardState'
import {
  parseTraceUrl,
  serializeTraceUrl,
  traceUrlForNode,
  traceUrlHasRoot,
  type TraceUrlState,
} from './traceUrlState'
import {
  layoutTraceGraph,
  supportedArtifactHref,
  type TraceFlowEdge,
  type TraceFlowNode,
  type TraceGraphLayout,
  type TraceNodeHierarchy,
} from './traceGraphLayout'
import {
  deriveTracePresentation,
  type TraceCompletionContext,
  type TracePresentation,
} from './tracePresentation'
import '@xyflow/react/dist/style.css'
import './trace.css'

const ROOT_NODE_LIMIT = 100
const DISABLED_TRACE_REQUEST: GraphTraceRequest = {
  naturalKey: 'trace-root',
  projectId: '00000000-0000-4000-8000-000000000000',
}

interface TraceRouteProps {
  readonly apiClient?: DashboardApiClient
}

export function TraceRoute({ apiClient = dashboardApi }: TraceRouteProps) {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: {
        gcTime: 0,
        retry: false,
      },
    },
  }))

  return (
    <QueryClientProvider client={queryClient}>
      <TraceScreen apiClient={apiClient} />
    </QueryClientProvider>
  )
}

function TraceScreen({ apiClient }: { readonly apiClient: DashboardApiClient }) {
  const location = useLocation()
  const navigate = useNavigate()
  const state = useMemo(() => parseTraceUrl(new URLSearchParams(location.search)), [location.search])
  const canonicalSearch = useMemo(() => serializeTraceUrl(state), [state])
  const queries = useMemo(() => createDashboardQueries(apiClient), [apiClient])
  const recoveryMessage = traceUrlRecoveryMessage(location.state)
  const rootNodesQuery = useQuery({
    ...queries.graphNodes({ limit: ROOT_NODE_LIMIT }),
    retry: false,
  })
  const traceRequest = traceRequestFor(state)
  const traceQuery = useQuery({
    ...queries.graphTrace(traceRequest ?? DISABLED_TRACE_REQUEST),
    enabled: traceRequest !== null,
    retry: false,
  })

  useEffect(() => {
    if (canonicalSearch !== location.search) {
      const message = state.invalidParameters.length === 0
        ? null
        : `잘못된 trace URL을 복구했습니다: ${state.invalidParameters.join(', ')}`
      navigate(
        { pathname: location.pathname, search: canonicalSearch },
        { replace: true, state: message === null ? null : { traceUrlRecovery: message } },
      )
    }
  }, [canonicalSearch, location.pathname, location.search, navigate, state.invalidParameters])

  function selectRoot(node: GraphNode) {
    navigate({
      pathname: '/trace',
      search: serializeTraceUrl(traceUrlForNode(node, state)),
    }, { state: null })
  }

  function updateTraceSettings(settings: Pick<TraceUrlState, 'direction' | 'maxDepth'>) {
    if (!traceUrlHasRoot(state)) {
      return
    }
    navigate({
      pathname: '/trace',
      search: serializeTraceUrl({ ...state, ...settings, invalidParameters: [] }),
    }, { state: null })
  }

  return (
    <section aria-label="계보 추적" className="trace-route">
      {recoveryMessage === null ? null : <p className="trace-route__validation" role="alert">{recoveryMessage}</p>}
      <TraceRootSelector
        error={rootNodesQuery.error}
        isLoading={rootNodesQuery.isPending}
        nodes={rootNodesQuery.data}
        onRetry={() => { void rootNodesQuery.refetch() }}
        onSelect={selectRoot}
        state={state}
      />
      {traceUrlHasRoot(state) ? (
        <TraceResult
          error={traceQuery.error}
          isLoading={traceQuery.isPending}
          onRetry={() => { void traceQuery.refetch() }}
          onSettingsChange={updateTraceSettings}
          state={state}
          trace={traceQuery.data}
        />
      ) : (
        <TraceReadyState />
      )}
    </section>
  )
}

interface TraceRootSelectorProps {
  readonly error: Error | null
  readonly isLoading: boolean
  readonly nodes: readonly GraphNode[] | undefined
  readonly onRetry: () => void
  readonly onSelect: (node: GraphNode) => void
  readonly state: TraceUrlState
}

function TraceRootSelector({ error, isLoading, nodes, onRetry, onSelect, state }: TraceRootSelectorProps) {
  if (isLoading) {
    return <LoadingState description="계보를 시작할 수 있는 노드를 확인하고 있습니다." title="추적 노드 목록을 불러오는 중" />
  }
  if (error !== null) {
    return <TraceError description="시작 노드 목록을 가져오지 못했습니다. 잠시 후 다시 시도하세요." onRetry={onRetry} title="추적 노드 목록을 표시할 수 없습니다" />
  }
  if (nodes === undefined || nodes.length === 0) {
    return <EmptyStatePanel description="현재 선택할 수 있는 graph node가 없습니다." title="추적할 노드가 없습니다" />
  }

  const sortedNodes = [...nodes].sort(compareGraphNodes)
  const selectedNodeId = selectedNodeIdFor(sortedNodes, state)

  function updateRoot(event: ChangeEvent<HTMLSelectElement>) {
    const node = sortedNodes.find((candidate) => candidate.nodeId === event.target.value)
    if (node !== undefined) {
      onSelect(node)
    }
  }

  return (
    <section aria-labelledby="trace-root-selector" className="trace-route__selector">
      <h2 id="trace-root-selector">시작 노드 선택</h2>
      <p>저장된 graph node를 선택합니다. 선택한 노드의 project, iteration, natural key만 trace 요청에 사용합니다.</p>
      <label htmlFor="trace-root-node">추적 시작 노드</label>
      <select id="trace-root-node" onChange={updateRoot} value={selectedNodeId}>
        <option value="">노드를 선택하세요</option>
        {sortedNodes.map((node) => (
          <option key={node.nodeId} value={node.nodeId}>{node.label} · {node.nodeKind} · {node.naturalKey}</option>
        ))}
      </select>
    </section>
  )
}

interface TraceResultProps {
  readonly error: Error | null
  readonly isLoading: boolean
  readonly onRetry: () => void
  readonly onSettingsChange: (settings: Pick<TraceUrlState, 'direction' | 'maxDepth'>) => void
  readonly state: TraceUrlState
  readonly trace: GraphTrace | undefined
}

function TraceResult({ error, isLoading, onRetry, onSettingsChange, state, trace }: TraceResultProps) {
  if (isLoading) {
    return <LoadingState description="선택한 시작 노드의 읽기 전용 계보를 계산하고 있습니다." title="계보를 불러오는 중" />
  }
  if (error !== null) {
    return <TraceError description="계보 데이터를 가져오지 못했습니다. URL과 시작 노드를 확인한 뒤 다시 시도하세요." onRetry={onRetry} title="계보를 표시할 수 없습니다" />
  }
  if (trace === undefined) {
    return null
  }

  return <LoadedTraceResult onSettingsChange={onSettingsChange} state={state} trace={trace} />
}

function LoadedTraceResult({ onSettingsChange, state, trace }: Pick<TraceResultProps, 'onSettingsChange' | 'state'> & { readonly trace: GraphTrace }) {
  const layout = useMemo(() => layoutTraceGraph(trace), [trace])
  const presentation = useMemo(() => deriveTracePresentation(trace, layout), [layout, trace])

  return (
    <section aria-label="계보 결과" className="trace-route__result">
      <TraceSettings onChange={onSettingsChange} state={state} />
      <TraceSummary layout={layout} presentation={presentation} />
      {trace.truncated ? (
        <DegradedState
          description={`최대 깊이 ${state.maxDepth}에서 응답이 잘렸습니다. 더 넓은 범위가 필요하면 최대 깊이를 늘리세요.`}
          title="일부 계보만 표시합니다"
        />
      ) : null}
      {layout.danglingEdges.length > 0 ? (
        <section aria-label="연결할 수 없는 간선" className="trace-route__warning" role="status">
          {layout.danglingEdges.length}개의 간선은 응답에 포함된 노드와 연결할 수 없어 canvas에서 제외했습니다. 아래 접근 가능한 목록에는 그대로 표시합니다.
        </section>
      ) : null}
      {presentation.isEmpty ? (
        <section aria-label="빈 계보 결과" className="trace-route__empty" role="status">
          시작 노드 외에 반환된 graph node나 연결된 edge가 없습니다.
        </section>
      ) : null}
      <TraceCanvas layout={layout} />
      <TraceAccessibleList layout={layout} root={trace.root} truncated={trace.truncated} />
    </section>
  )
}

function TraceSettings({ onChange, state }: { readonly onChange: (settings: Pick<TraceUrlState, 'direction' | 'maxDepth'>) => void; readonly state: TraceUrlState }) {
  function changeDirection(event: ChangeEvent<HTMLSelectElement>) {
    if (isGraphTraceDirection(event.target.value)) {
      onChange({ direction: event.target.value, maxDepth: state.maxDepth })
    }
  }

  function changeMaxDepth(event: ChangeEvent<HTMLSelectElement>) {
    const maxDepth = Number(event.target.value)
    if (Number.isInteger(maxDepth) && maxDepth >= 1 && maxDepth <= 10) {
      onChange({ direction: state.direction, maxDepth })
    }
  }

  return (
    <fieldset className="trace-route__settings">
      <legend>읽기 전용 trace 범위</legend>
      <label htmlFor="trace-direction">방향</label>
      <select id="trace-direction" onChange={changeDirection} value={state.direction}>
        <option value="BOTH">양방향</option>
        <option value="UPSTREAM">상위 방향</option>
        <option value="DOWNSTREAM">하위 방향</option>
      </select>
      <label htmlFor="trace-max-depth">최대 깊이</label>
      <select id="trace-max-depth" onChange={changeMaxDepth} value={state.maxDepth}>
        {Array.from({ length: 10 }, (_, index) => index + 1).map((depth) => (
          <option key={depth} value={depth}>{depth}</option>
        ))}
      </select>
    </fieldset>
  )
}

function TraceCanvas({ layout }: { readonly layout: TraceGraphLayout }) {
  const navigate = useNavigate()

  return (
    <section aria-label="계보 그래프" className="trace-route__canvas">
      <ReactFlow<TraceFlowNode, TraceFlowEdge>
        aria-label="읽기 전용 계보 그래프"
        defaultEdgeOptions={{ reconnectable: false, selectable: false }}
        deleteKeyCode={null}
        edges={layout.flowEdges}
        edgesFocusable={false}
        edgesReconnectable={false}
        elementsSelectable={false}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        nodes={layout.flowNodes}
        nodesConnectable={false}
        nodesDraggable={false}
        nodesFocusable={false}
        onNodeClick={(_, node) => {
          if (node.data.artifactHref !== null) {
            navigate(node.data.artifactHref)
          }
        }}
        selectionOnDrag={false}
      >
        <Background gap={20} />
      </ReactFlow>
    </section>
  )
}

function TraceSummary({ layout, presentation }: { readonly layout: TraceGraphLayout; readonly presentation: TracePresentation }) {
  return (
    <section aria-labelledby="trace-summary" className="trace-route__summary">
      <h2 id="trace-summary">계보 요약</h2>
      <p role="status">
        준비됨: API node {layout.nodeEntries.length}개, edge {layout.edgeEntries.length}개를 표시합니다.
        주 계보 edge {presentation.primaryEdgeCount}개와 보조 edge {presentation.secondaryEdgeCount}개는 각각 반환된 edge 유형으로만 구분했습니다.
      </p>
      <TraceCompletionContextView context={presentation.completionContext} />
    </section>
  )
}

function TraceCompletionContextView({ context }: { readonly context: TraceCompletionContext }) {
  if (context.availability === 'unavailable') {
    const description = context.reason === 'missing_canonical_lineage'
      ? '실제 graph edge와 canonical taskId가 함께 반환되지 않아 완료 작업 맥락을 표시할 수 없습니다.'
      : 'canonical lineage에서 명시적으로 완료된 작업 상태가 반환되지 않았습니다.'
    return (
      <section aria-label="완료 작업 맥락" className="trace-route__completion">
        <h3>완료 작업 맥락</h3>
        <p role="status">{description}</p>
      </section>
    )
  }

  return (
    <section aria-labelledby="trace-completion-context" className="trace-route__completion">
      <h3 id="trace-completion-context">canonical lineage의 완료 작업</h3>
      <ol>
        {context.completedTasks.map((task) => (
          <li key={task.taskId}>
            <article>
              <h4>{task.label}</h4>
              <p>작업 ID: {task.taskId}</p>
              <p>근거 edge 유형: {task.edgeTypes.join(', ')}</p>
              <p>수행 내용: {task.content ?? 'graph 응답에 기록되지 않았습니다.'}</p>
              <p>결과: {task.result ?? 'graph 응답에 기록되지 않았습니다.'}</p>
              <p>완료 시각: {task.completedAt ?? 'graph 응답에 기록되지 않았습니다.'}</p>
            </article>
          </li>
        ))}
      </ol>
    </section>
  )
}

function TraceAccessibleList({
  layout,
  root,
  truncated,
}: {
  readonly layout: TraceGraphLayout
  readonly root: GraphNode
  readonly truncated: boolean
}) {
  return (
    <section aria-labelledby="trace-accessible-list" className="trace-route__accessible-list">
      <h2 id="trace-accessible-list">계보 노드와 간선 목록</h2>
      <p>이 목록은 keyboard와 screen reader에서 canvas와 같은 API node, depth, edge, 주·보조 관계 정보를 제공합니다.</p>
      {truncated ? <p role="status">이 목록은 잘린 trace 응답과 동일한 node·edge만 포함합니다.</p> : null}
      <h3>시작 노드</h3>
      <p>{root.nodeId} · {root.nodeKind} · {root.naturalKey}</p>
      <h3>노드</h3>
      <ol>
        {layout.nodeEntries.map((entry) => (
          <li key={entry.node.nodeId}>
            <NodeSummary depth={entry.depth} hierarchy={nodeHierarchy(layout, entry.node.nodeId)} node={entry.node} />
          </li>
        ))}
      </ol>
      <h3>간선</h3>
      {layout.edgeEntries.length === 0 ? (
        <p role="status">표시할 간선이 없습니다.</p>
      ) : (
        <ol>
          {layout.edgeEntries.map(({ edge, hierarchy, isDangling }) => (
            <li key={`${edge.edgeId}:${edge.fromNodeId}:${edge.toNodeId}`}>
              <p>간선 ID: {edge.edgeId}</p>
              <p>유형: {edge.edgeType}</p>
              <p>계보 구분: {hierarchy === 'primary' ? '주 관계' : '보조 관계'}</p>
              <p>출발 노드 ID: {edge.fromNodeId}</p>
              <p>도착 노드 ID: {edge.toNodeId}</p>
              {isDangling ? <p role="status">이 간선은 응답에 없는 노드를 참조합니다.</p> : null}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

function NodeSummary({
  depth,
  hierarchy,
  node,
}: {
  readonly depth: number
  readonly hierarchy: TraceNodeHierarchy
  readonly node: GraphNode
}) {
  const href = supportedArtifactHref(node)
  const hierarchyLabel = hierarchy === 'primary'
    ? '주 계보 edge에 연결됨'
    : hierarchy === 'secondary'
      ? '보조 edge에 연결됨'
      : '반환된 edge 연결 없음'

  return (
    <article>
      <p>노드 ID: {node.nodeId}</p>
      <p>유형: {node.nodeKind}</p>
      <p>깊이: {depth}</p>
      <p>Natural key: {node.naturalKey}</p>
      <p>계보 구분: {hierarchyLabel}</p>
      {href === null ? <p>{node.label}</p> : <Link to={href}>{node.nodeKind} {node.label} 산출물 열기</Link>}
    </article>
  )
}

function TraceReadyState() {
  return (
    <section aria-label="trace 시작 안내" className="trace-route__hint" role="status">
      준비됨: 시작 노드를 선택하면 방향과 최대 깊이를 지정해 읽기 전용 계보를 확인할 수 있습니다.
    </section>
  )
}

function nodeHierarchy(layout: TraceGraphLayout, nodeId: string): TraceNodeHierarchy {
  return layout.flowNodes.find((node) => node.id === nodeId)?.data.hierarchy ?? 'unrelated'
}

function TraceError({ description, onRetry, title }: { readonly description: string; readonly onRetry: () => void; readonly title: string }) {
  return (
    <section className="trace-route__error">
      <ErrorState description={description} title={title} />
      <button onClick={onRetry} type="button">다시 시도</button>
    </section>
  )
}

function traceRequestFor(state: TraceUrlState): GraphTraceRequest | null {
  if (state.invalidParameters.length > 0 || state.projectId === null || state.naturalKey === null) {
    return null
  }
  return {
    direction: state.direction,
    maxDepth: state.maxDepth,
    naturalKey: state.naturalKey,
    ...(state.iterationId === null ? {} : { iterationId: state.iterationId }),
    projectId: state.projectId,
  }
}

function selectedNodeIdFor(nodes: readonly GraphNode[], state: TraceUrlState) {
  return nodes.find((node) => (
    node.projectId === state.projectId
    && node.iterationId === state.iterationId
    && node.naturalKey === state.naturalKey
  ))?.nodeId ?? ''
}

function compareGraphNodes(left: GraphNode, right: GraphNode) {
  return left.label.localeCompare(right.label) || left.naturalKey.localeCompare(right.naturalKey) || left.nodeId.localeCompare(right.nodeId)
}

function isGraphTraceDirection(value: string): value is GraphTraceDirection {
  return value === 'UPSTREAM' || value === 'DOWNSTREAM' || value === 'BOTH'
}

function traceUrlRecoveryMessage(value: unknown) {
  if (typeof value !== 'object' || value === null || !('traceUrlRecovery' in value)) {
    return null
  }
  return typeof value.traceUrlRecovery === 'string' ? value.traceUrlRecovery : null
}
