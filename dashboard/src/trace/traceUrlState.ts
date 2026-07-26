import type { GraphNode, GraphTraceDirection } from '../data-access'

export const DEFAULT_TRACE_DIRECTION: GraphTraceDirection = 'BOTH'
export const DEFAULT_TRACE_MAX_DEPTH = 3

const SAFE_IDENTIFIER_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,255}$/
const TRACE_QUERY_KEYS = new Set(['projectId', 'iterationId', 'naturalKey', 'direction', 'maxDepth'])

export interface TraceUrlState {
  readonly direction: GraphTraceDirection
  readonly invalidParameters: readonly string[]
  readonly iterationId: string | null
  readonly maxDepth: number
  readonly naturalKey: string | null
  readonly projectId: string | null
}

export function parseTraceUrl(params: URLSearchParams): TraceUrlState {
  const invalidParameters = invalidParametersFor(params)
  const projectId = parseSafeIdentifier(params.get('projectId'), 'projectId', invalidParameters)
  const iterationId = parseSafeIdentifier(params.get('iterationId'), 'iterationId', invalidParameters)
  const naturalKey = parseNaturalKey(params.get('naturalKey'), invalidParameters)
  const direction = parseDirection(params.get('direction'), invalidParameters)
  const maxDepth = parseMaxDepth(params.get('maxDepth'), invalidParameters)

  if ((projectId === null) !== (naturalKey === null)) {
    invalidParameters.push('projectId와 naturalKey는 함께 필요합니다')
  }

  return {
    direction,
    invalidParameters,
    iterationId,
    maxDepth,
    naturalKey: projectId === null ? null : naturalKey,
    projectId: naturalKey === null ? null : projectId,
  }
}

export function serializeTraceUrl(state: TraceUrlState): string {
  if (state.projectId === null || state.naturalKey === null) {
    return ''
  }

  const params = new URLSearchParams({
    direction: state.direction,
    maxDepth: String(state.maxDepth),
    naturalKey: state.naturalKey,
    projectId: state.projectId,
  })
  if (state.iterationId !== null) {
    params.set('iterationId', state.iterationId)
  }
  return `?${params.toString()}`
}

export function traceUrlForNode(node: GraphNode, state: Pick<TraceUrlState, 'direction' | 'maxDepth'>): TraceUrlState {
  return {
    direction: state.direction,
    invalidParameters: [],
    iterationId: node.iterationId,
    maxDepth: state.maxDepth,
    naturalKey: node.naturalKey,
    projectId: node.projectId,
  }
}

export function traceUrlHasRoot(state: TraceUrlState) {
  return state.projectId !== null && state.naturalKey !== null
}

function invalidParametersFor(params: URLSearchParams) {
  const invalidParameters: string[] = []
  const counts = new Map<string, number>()
  for (const [key] of params) {
    counts.set(key, (counts.get(key) ?? 0) + 1)
    if (!TRACE_QUERY_KEYS.has(key)) {
      invalidParameters.push(`${key}는 지원하지 않는 query parameter입니다`)
    }
  }
  for (const [key, count] of counts) {
    if (count > 1) {
      invalidParameters.push(`${key}는 한 번만 지정할 수 있습니다`)
    }
  }
  return invalidParameters
}

function parseSafeIdentifier(value: string | null, parameter: string, invalidParameters: string[]) {
  if (value === null) {
    return null
  }
  if (!SAFE_IDENTIFIER_PATTERN.test(value)) {
    invalidParameters.push(`${parameter} 형식이 올바르지 않습니다`)
    return null
  }
  return value
}

function parseNaturalKey(value: string | null, invalidParameters: string[]) {
  if (value === null) {
    return null
  }
  if (!SAFE_IDENTIFIER_PATTERN.test(value)) {
    invalidParameters.push('naturalKey 형식이 올바르지 않습니다')
    return null
  }
  return value
}

function parseDirection(value: string | null, invalidParameters: string[]): GraphTraceDirection {
  switch (value) {
    case null:
      return DEFAULT_TRACE_DIRECTION
    case 'UPSTREAM':
    case 'DOWNSTREAM':
    case 'BOTH':
      return value
    default:
      invalidParameters.push('direction은 UPSTREAM, DOWNSTREAM 또는 BOTH여야 합니다')
      return DEFAULT_TRACE_DIRECTION
  }
}

function parseMaxDepth(value: string | null, invalidParameters: string[]) {
  if (value === null) {
    return DEFAULT_TRACE_MAX_DEPTH
  }
  if (!/^\d+$/.test(value)) {
    invalidParameters.push('maxDepth는 1에서 10 사이의 정수여야 합니다')
    return DEFAULT_TRACE_MAX_DEPTH
  }
  const maxDepth = Number(value)
  if (maxDepth < 1 || maxDepth > 10) {
    invalidParameters.push('maxDepth는 1에서 10 사이의 정수여야 합니다')
    return DEFAULT_TRACE_MAX_DEPTH
  }
  return maxDepth
}
