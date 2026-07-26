import { gzipSync } from 'node:zlib'
import { afterEach, describe, expect, it } from 'vitest'

import { createBffServer, isAllowedBffRequestTarget, MAX_DECOMPRESSED_BODY_BYTES } from './server'

const projectId = '11111111-1111-4111-8111-111111111111'
const artifactId = '22222222-2222-4222-8222-222222222222'

describe('dashboard BFF allowlisted proxy', () => {
  const servers: ReturnType<typeof createBffServer>[] = []

  afterEach(async () => {
    await Promise.all(servers.splice(0).map((server) => server.close()))
  })

  it('forwards only the approved GET routes to the configured upstream origin', async () => {
    const { server, requests } = createTestServer()

    const requestsToAllow = [
      '/api/health',
      '/api/projects?limit=10',
      `/api/projects/${projectId}/iterations?cursor=next_page`,
      `/api/artifacts?projectId=${projectId}&artifactType=TASK`,
      `/api/artifacts/TASK/${artifactId}`,
      `/api/search/keyword?q=memory&projectId=${projectId}`,
      `/api/graph/nodes?projectId=${projectId}&nodeKind=DECISION&query=approval`,
      `/api/graph/trace?projectId=${projectId}&naturalKey=decision:ND-1&direction=BOTH&maxDepth=10`,
    ]

    for (const url of requestsToAllow) {
      const response = await server.inject({ method: 'GET', url, headers: { host: 'attacker.example', 'x-forwarded-host': 'attacker.example' } })
      expect(response.statusCode).toBe(200)
    }

    expect(requests.map(({ url }) => url.href)).toEqual([
      'https://memory.example/api/health',
      'https://memory.example/api/projects?limit=10',
      'https://memory.example/api/projects/11111111-1111-4111-8111-111111111111/iterations?cursor=next_page',
      'https://memory.example/api/artifacts?projectId=11111111-1111-4111-8111-111111111111&artifactType=TASK',
      'https://memory.example/api/artifacts/TASK/22222222-2222-4222-8222-222222222222',
      'https://memory.example/api/search/keyword?q=memory&projectId=11111111-1111-4111-8111-111111111111',
      'https://memory.example/api/graph/nodes?projectId=11111111-1111-4111-8111-111111111111&nodeKind=DECISION&query=approval',
      'https://memory.example/api/graph/trace?projectId=11111111-1111-4111-8111-111111111111&naturalKey=decision%3AND-1&direction=BOTH&maxDepth=10',
    ])
    expect(requests.every(({ init }) => !new Headers(init.headers).has('x-forwarded-host'))).toBe(true)
  })

  it('forwards the two approved JSON POST search routes after schema validation', async () => {
    const { server, requests } = createTestServer()

    const semantic = await server.inject({
      method: 'POST',
      url: '/api/search/semantic',
      headers: { 'content-type': 'application/json' },
      payload: JSON.stringify({ q: 'find decision', projectId, metadataFilters: { kind: 'decision' }, limit: 20 }),
    })
    const hybrid = await server.inject({
      method: 'POST',
      url: '/api/search/hybrid',
      headers: { 'content-type': 'application/json' },
      payload: JSON.stringify({ q: 'find decision', candidateLimit: 80, limit: 20, rrfK: 60 }),
    })

    expect(semantic.statusCode).toBe(200)
    expect(hybrid.statusCode).toBe(200)
    expect(requests.map(({ url }) => url.pathname)).toEqual(['/api/search/semantic', '/api/search/hybrid'])
    expect(requests.map(({ init }) => init.headers)).toEqual([
      { accept: 'application/json', 'content-type': 'application/json' },
      { accept: 'application/json', 'content-type': 'application/json' },
    ])
    expect(requests.map(({ init }) => init.body)).toEqual([
      JSON.stringify({ q: 'find decision', projectId, metadataFilters: { kind: 'decision' }, limit: 20 }),
      JSON.stringify({ q: 'find decision', candidateLimit: 80, limit: 20, rrfK: 60 }),
    ])
  })

  it('rejects writes, unallowlisted routes, unsafe targets, and method overrides before fetching upstream', async () => {
    const { server, requests } = createTestServer()

    const rejectedRequests = [
      { method: 'POST' as const, url: '/api/projects', headers: { 'content-type': 'application/json' }, payload: '{}' },
      { method: 'PUT' as const, url: '/api/search/semantic', headers: { 'content-type': 'application/json' }, payload: '{}' },
      { method: 'GET' as const, url: '/api/embedding-jobs' },
      { method: 'GET' as const, url: '/api//health' },
      { method: 'GET' as const, url: `/api/projects/${projectId}/../iterations` },
      { method: 'GET' as const, url: `/api/artifacts/TASK/${artifactId}%2Fother` },
      { method: 'GET' as const, url: '/api/search/keyword?q=%E0%A4%A' },
      { method: 'GET' as const, url: '/api/search/keyword?q=memory&q=duplicate' },
      { method: 'GET' as const, url: '/api/projects?limit=10&cursor=next_page&extra=value' },
      { method: 'GET' as const, url: '/api/search/keyword?q=memory&url=https%3A%2F%2Fattacker.example' },
      { method: 'GET' as const, url: '/api/artifacts?sourcePath=..%2Fsecret' },
      { method: 'GET' as const, url: '/api/health', headers: { 'x-http-method-override': 'POST' } },
    ]

    for (const request of rejectedRequests) {
      const response = await server.inject(request)
      expect(response.statusCode, request.url).toBeGreaterThanOrEqual(400)
    }

    expect(requests).toHaveLength(0)
  })

  it('rejects raw backslash request targets before route matching can normalize them', () => {
    expect(isAllowedBffRequestTarget('/api\\health')).toBe(false)
  })

  it('rejects non-JSON, invalid schema bodies, and decompressed payloads over 64 KiB before fetching upstream', async () => {
    const { server, requests } = createTestServer()

    const nonJson = await server.inject({
      method: 'POST',
      url: '/api/search/semantic',
      headers: { 'content-type': 'text/plain' },
      payload: '{"q":"memory"}',
    })
    const invalidSchema = await server.inject({
      method: 'POST',
      url: '/api/search/semantic',
      headers: { 'content-type': 'application/json' },
      payload: JSON.stringify({ q: 'memory', upstreamOrigin: 'https://attacker.example' }),
    })
    const compressedPayload = gzipSync(Buffer.from(JSON.stringify({ q: 'x'.repeat(MAX_DECOMPRESSED_BODY_BYTES) })))
    const oversized = await server.inject({
      method: 'POST',
      url: '/api/search/hybrid',
      headers: { 'content-encoding': 'gzip', 'content-type': 'application/json' },
      payload: compressedPayload,
    })

    expect(nonJson.statusCode).toBe(415)
    expect(invalidSchema.statusCode).toBe(400)
    expect(oversized.statusCode).toBe(413)
    expect(requests).toHaveLength(0)
  })

  function createTestServer() {
    const requests: { init: RequestInit; url: URL }[] = []
    const server = createBffServer({
      upstreamOrigin: 'https://memory.example',
      fetch: async (input, init = {}) => {
        requests.push({ init, url: new URL(input.toString()) })
        return new Response(JSON.stringify({ status: 'UP' }), {
          headers: { 'content-type': 'application/json' },
        })
      },
    })
    servers.push(server)
    return { server, requests }
  }
})
