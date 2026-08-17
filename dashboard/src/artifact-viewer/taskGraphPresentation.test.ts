import { describe, expect, it } from 'vitest'
import {
  isTaskGraph,
  isTaskGraphTask,
  parseTaskGraphContent,
  summarizeTaskGraphContent,
} from './taskGraphPresentation'

const schemaVersion = 'p2a.task_graph.v1'

function graphContent(tasks: readonly unknown[]): string {
  return JSON.stringify({ schema_version: schemaVersion, tasks })
}

function task(overrides: Readonly<Record<string, unknown>> = {}): Readonly<Record<string, unknown>> {
  return {
    dependencies: [],
    id: 'task-001',
    sourceSpecRefs: ['implementation.interfaces'],
    status: 'todo',
    ...overrides,
  }
}

describe('task graph presentation', () => {
  it('summarizes an empty supported graph', () => {
    expect(summarizeTaskGraphContent(graphContent([]))).toEqual({
      availability: 'available',
      summary: {
        dependencyCount: 0,
        sourceSpecRefs: [],
        statusCounts: [],
        taskCount: 0,
      },
    })
  })

  it('summarizes mixed task statuses and source specification references deterministically', () => {
    const content = graphContent([
      task({ id: 'task-001', sourceSpecRefs: ['product.scope'], status: 'done' }),
      task({ id: 'task-002', sourceSpecRefs: ['implementation.interfaces', 'product.scope'], status: 'in_progress' }),
      task({ id: 'task-003', sourceSpecRefs: ['implementation.interfaces'], status: 'todo' }),
      task({ id: 'task-004', sourceSpecRefs: ['product.constraints'], status: 'blocked' }),
    ])

    expect(summarizeTaskGraphContent(content)).toEqual({
      availability: 'available',
      summary: {
        currentTaskId: 'task-002',
        dependencyCount: 0,
        sourceSpecRefs: ['product.scope', 'implementation.interfaces', 'product.constraints'],
        statusCounts: [
          { count: 1, status: 'todo' },
          { count: 1, status: 'blocked' },
          { count: 1, status: 'in_progress' },
          { count: 1, status: 'done' },
        ],
        taskCount: 4,
      },
    })
  })

  it('counts every declared task dependency', () => {
    const content = graphContent([
      task({ dependencies: ['task-001', 'task-002'], id: 'task-003' }),
      task({ dependencies: ['task-001'], id: 'task-004' }),
    ])

    expect(summarizeTaskGraphContent(content)).toEqual({
      availability: 'available',
      summary: {
        dependencyCount: 3,
        sourceSpecRefs: ['implementation.interfaces'],
        statusCounts: [{ count: 2, status: 'todo' }],
        taskCount: 2,
      },
    })
  })

  it('omits current-task context when no task is in progress', () => {
    const result = summarizeTaskGraphContent(graphContent([
      task({ id: 'task-001', status: 'todo' }),
      task({ id: 'task-002', status: 'done' }),
    ]))

    expect(result).toMatchObject({ availability: 'available' })
    if (result.availability === 'available') {
      expect(result.summary).not.toHaveProperty('currentTaskId')
    }
  })

  it('returns unavailable derived information for malformed JSON', () => {
    expect(summarizeTaskGraphContent('{"schema_version":')).toEqual({
      availability: 'unavailable',
      reason: 'malformed_json',
    })
    expect(parseTaskGraphContent(null)).toEqual({
      availability: 'unavailable',
      reason: 'malformed_json',
    })
  })

  it('returns unavailable derived information for unsupported schema versions', () => {
    expect(summarizeTaskGraphContent(JSON.stringify({ schema_version: 'p2a.task_graph.v2', tasks: [] }))).toEqual({
      availability: 'unavailable',
      reason: 'unsupported_schema_version',
    })
  })

  it('rejects invalid task shapes without attempting to summarize them', () => {
    const invalidTask = task({ dependencies: 'task-001' })

    expect(isTaskGraphTask(invalidTask)).toBe(false)
    expect(isTaskGraph({ schema_version: schemaVersion, tasks: [invalidTask] })).toBe(false)
    expect(summarizeTaskGraphContent(graphContent([invalidTask]))).toEqual({
      availability: 'unavailable',
      reason: 'invalid_task_shape',
    })
  })
})
