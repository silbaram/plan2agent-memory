import { gzipSync } from 'node:zlib'
import { afterEach, describe, expect, it } from 'vitest'

import { createBffServer, isAllowedBffRequestTarget, MAX_DECOMPRESSED_BODY_BYTES } from './server'

const projectId = '11111111-1111-4111-8111-111111111111'
const artifactId = '22222222-2222-4222-8222-222222222222'
const serverLocalToken = 'server-only-synthetic-token'
const browserSuppliedToken = 'browser-controlled-synthetic-token'

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
      `/api/artifacts?projectId=${projectId}&artifactTypes=TASK&artifactTypes=RUN_RECORD`,
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
      'https://memory.example/api/artifacts?projectId=11111111-1111-4111-8111-111111111111&artifactTypes=TASK&artifactTypes=RUN_RECORD',
      'https://memory.example/api/artifacts/TASK/22222222-2222-4222-8222-222222222222',
      'https://memory.example/api/search/keyword?q=memory&projectId=11111111-1111-4111-8111-111111111111',
      'https://memory.example/api/graph/nodes?projectId=11111111-1111-4111-8111-111111111111&nodeKind=DECISION&query=approval',
      'https://memory.example/api/graph/trace?projectId=11111111-1111-4111-8111-111111111111&naturalKey=decision%3AND-1&direction=BOTH&maxDepth=10',
    ])
    expect(requests.every(({ init }) => !new Headers(init.headers).has('x-forwarded-host'))).toBe(true)
    expect(requests.every(({ init }) => new Headers(init.headers).get('x-p2a-local-token') === serverLocalToken)).toBe(true)
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
      {
        accept: 'application/json',
        'content-type': 'application/json',
        'x-p2a-local-token': serverLocalToken,
      },
      {
        accept: 'application/json',
        'content-type': 'application/json',
        'x-p2a-local-token': serverLocalToken,
      },
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
      { method: 'GET' as const, url: '/api/artifacts?limit=10&limit=20' },
      { method: 'GET' as const, url: '/api/artifacts?artifactTypes=DOCUMENT_CHUNK' },
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

  it('fails closed when no header-safe server-only token is configured', () => {
    expect(() => createBffServer({ localToken: ' ' })).toThrow('localToken must be a non-empty header-safe value')
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

  it('injects the server-only token for GET requests without forwarding browser credentials', async () => {
    const { server, requests } = createTestServer()

    const response = await server.inject({
      method: 'GET',
      url: '/api/projects?limit=10',
      headers: browserCredentialHeaders(),
    })

    expect(response.statusCode).toBe(200)
    expect(server.log.level).toBe('silent')
    expect(requests).toHaveLength(1)
    expectApprovedUpstreamHeaders(requests[0]?.init, false)
  })

  it('injects the server-only token for POST requests without forwarding browser credentials', async () => {
    const { server, requests } = createTestServer()

    const response = await server.inject({
      method: 'POST',
      url: '/api/search/semantic',
      headers: {
        'content-type': 'application/json',
        ...browserCredentialHeaders(),
      },
      payload: JSON.stringify({ q: 'find decision', projectId }),
    })

    expect(response.statusCode).toBe(200)
    expect(requests).toHaveLength(1)
    expectApprovedUpstreamHeaders(requests[0]?.init, true)
  })

  it('redacts the local token and strips upstream credential headers from GET and POST responses', async () => {
    const { server, requests } = createTestServer(() => new Response(JSON.stringify({ localToken: serverLocalToken }), {
      headers: {
        authorization: `Bearer ${serverLocalToken}`,
        connection: 'close',
        'content-type': 'application/json; charset=utf-8',
        cookie: `session=${serverLocalToken}`,
        'keep-alive': 'timeout=5',
        'proxy-authenticate': `Bearer ${serverLocalToken}`,
        'set-cookie': `session=${serverLocalToken}`,
        'www-authenticate': `Bearer ${serverLocalToken}`,
        'x-api-key': serverLocalToken,
        'x-p2a-local-token': serverLocalToken,
      },
    }))

    const getResponse = await server.inject({ method: 'GET', url: '/api/health' })
    const postResponse = await server.inject({
      method: 'POST',
      url: '/api/search/hybrid',
      headers: { 'content-type': 'application/json' },
      payload: JSON.stringify({ q: 'find decision' }),
    })

    for (const response of [getResponse, postResponse]) {
      expect(response.statusCode).toBe(200)
      expect(response.body).not.toContain(serverLocalToken)
      expect(JSON.stringify(response.headers)).not.toContain(serverLocalToken)
      expect(response.headers['content-type']).toBe('application/json')
      expect(response.headers.authorization).toBeUndefined()
      expect(response.headers.connection).toBeUndefined()
      expect(response.headers.cookie).toBeUndefined()
      expect(response.headers['keep-alive']).toBeUndefined()
      expect(response.headers['proxy-authenticate']).toBeUndefined()
      expect(response.headers['set-cookie']).toBeUndefined()
      expect(response.headers['www-authenticate']).toBeUndefined()
      expect(response.headers['x-api-key']).toBeUndefined()
      expect(response.headers['x-p2a-local-token']).toBeUndefined()
    }
    expect(requests).toHaveLength(2)
  })

  it('returns stable secret-free GET and POST errors without attempting upstream fetches for rejected input', async () => {
    const { server, requests } = createTestServer(() => {
      throw new Error(`upstream error contains ${serverLocalToken}`)
    })

    const rejectedGet = await server.inject({
      method: 'GET',
      url: '/api/search/keyword?q=memory&q=duplicate',
      headers: browserCredentialHeaders(),
    })
    const rejectedPost = await server.inject({
      method: 'POST',
      url: '/api/search/semantic',
      headers: {
        'content-type': 'application/json',
        ...browserCredentialHeaders(),
      },
      payload: JSON.stringify({ q: 'memory', upstreamOrigin: `https://${serverLocalToken}.example` }),
    })

    expect(rejectedGet.statusCode).toBe(400)
    expect(rejectedPost.statusCode).toBe(400)
    expect(rejectedGet.body).not.toContain(serverLocalToken)
    expect(rejectedPost.body).not.toContain(serverLocalToken)
    expect(requests).toHaveLength(0)
  })

  it('returns stable secret-free GET and POST errors when the upstream fails', async () => {
    const { server, requests } = createTestServer(() => {
      throw new Error(`upstream error contains ${serverLocalToken}`)
    })

    const getResponse = await server.inject({ method: 'GET', url: '/api/health' })
    const postResponse = await server.inject({
      method: 'POST',
      url: '/api/search/semantic',
      headers: { 'content-type': 'application/json' },
      payload: JSON.stringify({ q: 'memory' }),
    })

    for (const response of [getResponse, postResponse]) {
      expect(response.statusCode).toBe(500)
      expect(response.json()).toEqual({ error: 'Upstream request failed' })
      expect(response.body).not.toContain(serverLocalToken)
    }
    expect(requests).toHaveLength(2)
  })

  function createTestServer(upstreamResponse: () => Response = () => new Response(JSON.stringify({ status: 'UP' }), {
    headers: { 'content-type': 'application/json' },
  })) {
    const requests: { init: RequestInit; url: URL }[] = []
    const server = createBffServer({
      localToken: serverLocalToken,
      upstreamOrigin: 'https://memory.example',
      fetch: async (input, init = {}) => {
        requests.push({ init, url: new URL(input.toString()) })
        return upstreamResponse()
      },
    })
    servers.push(server)
    return { server, requests }
  }

  function browserCredentialHeaders() {
    return {
      authorization: `Bearer ${browserSuppliedToken}`,
      connection: 'keep-alive',
      cookie: `session=${browserSuppliedToken}`,
      host: 'attacker.example',
      'keep-alive': 'timeout=5',
      'proxy-authorization': `Bearer ${browserSuppliedToken}`,
      'x-api-key': browserSuppliedToken,
      'x-access-token': browserSuppliedToken,
      'x-auth-token': browserSuppliedToken,
      'x-p2a-local-token': browserSuppliedToken,
    }
  }

  function expectApprovedUpstreamHeaders(init: RequestInit | undefined, isPost: boolean) {
    const headers = new Headers(init?.headers)

    expect(headers.get('accept')).toBe('application/json')
    expect(headers.get('content-type')).toBe(isPost ? 'application/json' : null)
    expect(headers.get('x-p2a-local-token')).toBe(serverLocalToken)
    expect([...headers.keys()].sort()).toEqual(
      isPost
        ? ['accept', 'content-type', 'x-p2a-local-token']
        : ['accept', 'x-p2a-local-token'],
    )
    for (const headerName of [
      'authorization',
      'connection',
      'cookie',
      'host',
      'keep-alive',
      'proxy-authorization',
      'x-api-key',
      'x-access-token',
      'x-auth-token',
    ]) {
      expect(headers.has(headerName)).toBe(false)
    }
  }
})
