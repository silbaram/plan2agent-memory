import type { TaskGraphStatusCount, TaskGraphSummary } from './artifactPresentation'

const supportedTaskGraphSchemaVersion = 'p2a.task_graph.v1'

const taskStatuses = ['todo', 'blocked', 'in_progress', 'done'] as const

export type TaskGraphStatus = typeof taskStatuses[number]

export type TaskGraphUnavailableReason =
  | 'malformed_json'
  | 'unsupported_schema_version'
  | 'invalid_task_graph'
  | 'invalid_task_shape'

export interface TaskGraphTask {
  readonly id: string
  readonly status: TaskGraphStatus
  readonly dependencies: readonly string[]
  readonly sourceSpecRefs: readonly string[]
}

export interface TaskGraph {
  readonly schemaVersion: typeof supportedTaskGraphSchemaVersion
  readonly tasks: readonly TaskGraphTask[]
}

export interface AvailableTaskGraph {
  readonly availability: 'available'
  readonly taskGraph: TaskGraph
}

export interface UnavailableTaskGraph {
  readonly availability: 'unavailable'
  readonly reason: TaskGraphUnavailableReason
}

export type TaskGraphParseResult = AvailableTaskGraph | UnavailableTaskGraph

export interface AvailableTaskGraphDerivedInfo {
  readonly availability: 'available'
  readonly summary: TaskGraphSummary
}

export type TaskGraphDerivedInfo = AvailableTaskGraphDerivedInfo | UnavailableTaskGraph

export function parseTaskGraphContent(rawContent: unknown): TaskGraphParseResult {
  if (typeof rawContent !== 'string') {
    return unavailable('malformed_json')
  }

  let content: unknown
  try {
    content = JSON.parse(rawContent)
  } catch {
    return unavailable('malformed_json')
  }

  return parseTaskGraph(content)
}

export function parseTaskGraph(value: unknown): TaskGraphParseResult {
  if (!isRecord(value) || !hasOwn(value, 'schema_version') || !hasOwn(value, 'tasks')) {
    return unavailable('invalid_task_graph')
  }

  if (value.schema_version !== supportedTaskGraphSchemaVersion) {
    return unavailable('unsupported_schema_version')
  }

  if (!Array.isArray(value.tasks)) {
    return unavailable('invalid_task_graph')
  }

  const tasks: TaskGraphTask[] = []
  for (const taskValue of value.tasks) {
    const task = parseTaskGraphTask(taskValue)
    if (task === null) {
      return unavailable('invalid_task_shape')
    }
    tasks.push(task)
  }

  return {
    availability: 'available',
    taskGraph: {
      schemaVersion: supportedTaskGraphSchemaVersion,
      tasks,
    },
  }
}

export function isTaskGraph(value: unknown): value is TaskGraph {
  return parseTaskGraph(value).availability === 'available'
}

export function isTaskGraphTask(value: unknown): value is TaskGraphTask {
  return parseTaskGraphTask(value) !== null
}

export function summarizeTaskGraph(taskGraph: TaskGraph): TaskGraphSummary {
  const sourceSpecRefs = new Set<string>()
  const statusTotals = new Map<TaskGraphStatus, number>()
  let dependencyCount = 0
  let currentTaskId: string | undefined

  for (const task of taskGraph.tasks) {
    dependencyCount += task.dependencies.length
    statusTotals.set(task.status, (statusTotals.get(task.status) ?? 0) + 1)
    for (const sourceSpecRef of task.sourceSpecRefs) {
      sourceSpecRefs.add(sourceSpecRef)
    }
    if (currentTaskId === undefined && task.status === 'in_progress') {
      currentTaskId = task.id
    }
  }

  const summary = {
    dependencyCount,
    sourceSpecRefs: [...sourceSpecRefs],
    statusCounts: createStatusCounts(statusTotals),
    taskCount: taskGraph.tasks.length,
  }

  return currentTaskId === undefined ? summary : { ...summary, currentTaskId }
}

export function summarizeTaskGraphContent(rawContent: unknown): TaskGraphDerivedInfo {
  const parsed = parseTaskGraphContent(rawContent)
  if (parsed.availability === 'unavailable') {
    return parsed
  }

  return {
    availability: 'available',
    summary: summarizeTaskGraph(parsed.taskGraph),
  }
}

function parseTaskGraphTask(value: unknown): TaskGraphTask | null {
  if (!isRecord(value)) {
    return null
  }

  const id = readNonEmptyString(value, 'id')
  const status = parseTaskGraphStatus(value.status)
  const dependencies = readStringArray(value.dependencies)
  const sourceSpecRefs = readNonEmptyStringArray(value.sourceSpecRefs)
  if (id === null || status === null || dependencies === null || sourceSpecRefs === null) {
    return null
  }

  return { id, status, dependencies, sourceSpecRefs }
}

function parseTaskGraphStatus(value: unknown): TaskGraphStatus | null {
  return taskStatuses.find((status) => status === value) ?? null
}

function readNonEmptyString(record: Readonly<Record<string, unknown>>, field: string): string | null {
  const value = record[field]
  return typeof value === 'string' && value.trim().length > 0 ? value : null
}

function readStringArray(value: unknown): readonly string[] | null {
  if (!Array.isArray(value)) {
    return null
  }

  const strings: string[] = []
  for (const entry of value) {
    if (typeof entry !== 'string' || entry.trim().length === 0) {
      return null
    }
    strings.push(entry)
  }
  return strings
}

function readNonEmptyStringArray(value: unknown): readonly string[] | null {
  const strings = readStringArray(value)
  return strings === null || strings.length === 0 ? null : strings
}

function createStatusCounts(statusTotals: ReadonlyMap<TaskGraphStatus, number>): readonly TaskGraphStatusCount[] {
  const statusCounts: TaskGraphStatusCount[] = []
  for (const status of taskStatuses) {
    const count = statusTotals.get(status)
    if (count !== undefined) {
      statusCounts.push({ status, count })
    }
  }
  return statusCounts
}

function unavailable(reason: TaskGraphUnavailableReason): UnavailableTaskGraph {
  return { availability: 'unavailable', reason }
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function hasOwn(record: Readonly<Record<string, unknown>>, field: string): boolean {
  return Object.hasOwn(record, field)
}
