import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { DashboardApiError, type ArtifactDetail, type ArtifactDetailRequest, type DashboardApiClient } from '../data-access'
import { ArtifactViewer } from './ArtifactViewer'

const artifactId = '22222222-2222-4222-8222-222222222222'

function createArtifact(overrides: Partial<ArtifactDetail> = {}): ArtifactDetail {
  return {
    artifactId,
    artifactType: 'TASK',
    createdAt: '2026-07-26T09:00:00Z',
    iterationId: 'iteration-1',
    lineage: {
      artifactRefs: [{ artifactId: 'run-1', artifactType: 'RUN_RECORD', sourcePath: 'runs/run-1.json' }],
      contentHash: 'a'.repeat(64),
      documentId: 'document-1',
      iterationId: 'iteration-1',
      projectId: 'project-1',
      runId: 'run-1',
      snapshotVersion: 2,
      taskGraphId: 'graph-1',
      taskId: 'task-1',
    },
    mediaType: 'text/plain',
    metadata: { owner: 'memory', phase: 'gate-c' },
    projectId: 'project-1',
    rawContent: 'Stored plain-text content',
    source: {
      sourceDocumentId: 'document-1',
      sourceIterationId: 'source-iteration-1',
      sourcePath: 'docs/task.md',
      sourceProjectId: 'source-project-1',
      sourceReference: {
        canonicalServerId: 'server-1',
        endLine: 42,
        fragment: 'acceptance',
        path: 'docs/task.md',
        startLine: 12,
        uri: 'p2a://memory/artifacts/task-1',
      },
      sourceRunId: 'source-run-1',
      sourceTaskGraphId: 'source-graph-1',
      sourceTaskId: 'source-task-1',
    },
    title: 'Stored task',
    updatedAt: '2026-07-26T09:30:00Z',
    ...overrides,
  }
}

function createClient(resolver: (request: ArtifactDetailRequest) => Promise<ArtifactDetail>): Pick<DashboardApiClient, 'getArtifact'> {
  return { getArtifact: resolver }
}

afterEach(() => {
  cleanup()
})

describe('ArtifactViewer', () => {
  it('loads the requested type and id and presents stored project, source, and lineage metadata', async () => {
    const requests: ArtifactDetailRequest[] = []
    const client = createClient(async (request) => {
      requests.push(request)
      return createArtifact()
    })

    render(<ArtifactViewer artifactId={` ${artifactId} `} artifactType="TASK" client={client} />)

    expect(await screen.findByRole('heading', { name: 'Stored task' })).toBeTruthy()
    expect(requests).toEqual([{ artifactId, artifactType: 'TASK' }])
    expect(screen.getByRole('heading', { name: '산출물 메타데이터' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: '원본 메타데이터' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: '계보 메타데이터' })).toBeTruthy()
    expect(screen.getByText('source-project-1')).toBeTruthy()
    expect(screen.getByText('p2a://memory/artifacts/task-1')).toBeTruthy()
    expect(screen.getByText('runs/run-1.json')).toBeTruthy()
    expect(screen.getByRole('tab', { name: '미리보기' }).getAttribute('aria-selected')).toBe('true')
  })

  it('renders Markdown with a fixed sanitizer while blocking raw HTML, unsafe links, and external images', async () => {
    const markdown = [
      '# 안전한 결정',
      '',
      '[안전한 링크](https://example.com/decision)',
      '[앵커 링크](#metadata)',
      '[메일 링크](mailto:owner@example.com)',
      '[상대 링크](docs/decision.md)',
      '[루트 링크](/docs/decision.md)',
      '[쿼리 링크](?view=raw)',
      '[스크립트 링크](javascript:alert(1))',
      '[데이터 링크](data:text/html,unsafe)',
      '[파일 링크](file:///etc/passwd)',
      '![외부 이미지](https://example.com/image.png)',
      '<div>원시 HTML</div>',
    ].join('\n\n')
    const client = createClient(async () => createArtifact({ mediaType: 'text/markdown', rawContent: markdown }))

    render(<ArtifactViewer artifactId={artifactId} artifactType="TASK" client={client} />)

    expect(await screen.findByRole('heading', { level: 1, name: '안전한 결정' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '안전한 링크' }).getAttribute('href')).toBe('https://example.com/decision')
    expect(screen.getByRole('link', { name: '앵커 링크' }).getAttribute('href')).toBe('#metadata')
    expect(screen.queryByRole('link', { name: '메일 링크' })).toBeNull()
    expect(screen.queryByRole('link', { name: '상대 링크' })).toBeNull()
    expect(screen.queryByRole('link', { name: '루트 링크' })).toBeNull()
    expect(screen.queryByRole('link', { name: '쿼리 링크' })).toBeNull()
    expect(screen.queryByRole('link', { name: '스크립트 링크' })).toBeNull()
    expect(screen.queryByRole('link', { name: '데이터 링크' })).toBeNull()
    expect(screen.queryByRole('link', { name: '파일 링크' })).toBeNull()
    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.queryByText('원시 HTML')).toBeNull()

    fireEvent.click(screen.getByRole('tab', { name: '원문' }))

    expect(screen.getByRole('tabpanel').textContent).toContain('<div>원시 HTML</div>')
  })

  it('formats valid JSON only in the preview tab and preserves its original text in the raw tab', async () => {
    const rawContent = '{"task":"review","rank":2}'
    const client = createClient(async () => createArtifact({ mediaType: 'application/json; charset=utf-8', rawContent }))

    render(<ArtifactViewer artifactId={artifactId} artifactType="TASK" client={client} />)

    expect((await screen.findByLabelText('형식화된 JSON 미리보기')).textContent).toBe('{\n  "task": "review",\n  "rank": 2\n}')

    fireEvent.click(screen.getByRole('tab', { name: '원문' }))

    expect(screen.getByLabelText('이스케이프된 원문').textContent).toBe(rawContent)
  })

  it('keeps invalid JSON as escaped raw text instead of attempting a formatted preview', async () => {
    const rawContent = '{"task":'
    const client = createClient(async () => createArtifact({ mediaType: 'application/json', rawContent }))

    render(<ArtifactViewer artifactId={artifactId} artifactType="TASK" client={client} />)

    expect((await screen.findByText('JSON 미리보기를 표시할 수 없습니다. 원문 탭에서 저장된 내용을 확인하세요.')).textContent).toContain('JSON 미리보기를 표시할 수 없습니다')

    fireEvent.click(screen.getByRole('tab', { name: '원문' }))

    expect(screen.getByLabelText('이스케이프된 원문').textContent).toBe(rawContent)
  })

  it.each([
    ['not_found', 404, '산출물을 찾을 수 없습니다'],
    ['invalid_request', 400, '유효하지 않은 산출물 요청'],
    ['bff_response_too_large', 502, '산출물 응답이 너무 큽니다'],
  ] as const)('renders the safe %s error state', async (code, status, title) => {
    const client = createClient(async () => {
      throw new DashboardApiError(code, status, 'private backend detail')
    })

    render(<ArtifactViewer artifactId={artifactId} artifactType="TASK" client={client} />)

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain(title)
    expect(alert.textContent).not.toContain('private backend detail')
    if (code === 'bff_response_too_large') {
      expect(alert.textContent).toContain('10 MiB')
    }
  })

  it('rejects an unsupported artifact type without making a data request', () => {
    let requestCount = 0
    const client = createClient(async () => {
      requestCount += 1
      return createArtifact()
    })

    render(<ArtifactViewer artifactId={artifactId} artifactType="PROJECT" client={client} />)

    expect(screen.getByRole('alert').textContent).toContain('지원하지 않는 산출물 유형')
    expect(requestCount).toBe(0)
  })
})
