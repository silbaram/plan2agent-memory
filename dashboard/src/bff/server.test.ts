import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { gzipSync } from 'node:zlib'
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  createBffServer,
  isAllowedBffRequestTarget,
  MAX_DECOMPRESSED_BODY_BYTES,
  MAX_UPSTREAM_RESPONSE_BYTES,
  UPSTREAM_TIMEOUT_MILLISECONDS,
} from './server'
import type { BffOptions } from './server'

const projectId = '11111111-1111-4111-8111-111111111111'
const artifactId = '22222222-2222-4222-8222-222222222222'
const serverLocalToken = 'server-only-synthetic-token'
const browserSuppliedToken = 'browser-controlled-synthetic-token'
const sensitiveQuery = 'synthetic-private-query'
const sensitiveBody = 'synthetic-private-body'
const internalUpstreamUrl = 'http://127.0.0.1:8080/internal-only'
const partialResponseMarker = 'synthetic-partial-response'
const syntheticStackMarker = 'synthetic-upstream-stack'

type TestFetch = NonNullable<BffOptions['fetch']>
type TestServerOptions = Pick<BffOptions, 'distDir'>

const expectedDashboardCsp = [
  "default-src 'self'",
  "base-uri 'none'",
  "object-src 'none'",
  "frame-ancestors 'none'",
  "form-action 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "style-src-attr 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'self'",
  "manifest-src 'self'",
  "worker-src 'self'",
].join('; ')

describe('dashboard BFF allowlisted proxy', () => {
  const servers: ReturnType<typeof createBffServer>[] = []
  const staticDirectories: string[] = []

  afterEach(async () => {
    await Promise.all([
      ...servers.splice(0).map((server) => server.close()),
      ...staticDirectories.splice(0).map((directory) => rm(directory, { force: true, recursive: true })),
    ])
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
      const response = await server.inject({ method: 'GET', url, headers: { host: 'localhost', 'x-forwarded-host': 'attacker.example' } })
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

  it('requires a loopback Host and same-origin browser metadata before fetching upstream', async () => {
    const { server, requests } = createTestServer()
    const rejectedRequests = [
      { host: 'attacker.example' },
      { host: 'localhost:4173', origin: 'http://attacker.example' },
      { host: 'localhost:4173', origin: 'https://localhost:4173' },
      { host: 'localhost:4173', origin: 'http://localhost:4173', 'sec-fetch-site': 'cross-site' },
      { host: 'localhost:4173', origin: 'http://localhost:4173', 'sec-fetch-site': 'same-site' },
    ]

    for (const headers of rejectedRequests) {
      const response = await server.inject({
        method: 'GET',
        url: `/api/search/keyword?q=${sensitiveQuery}`,
        headers,
      })

      expect(response.statusCode).toBe(403)
      expect(response.json()).toEqual({ error: 'Forbidden request context' })
      expect(response.body).not.toContain(sensitiveQuery)
      expect(JSON.stringify(response.headers)).not.toContain('attacker.example')
    }

    const accepted = await server.inject({
      method: 'GET',
      url: '/api/health',
      headers: trustedBrowserHeaders(),
    })

    expect(accepted.statusCode).toBe(200)
    expect(requests).toHaveLength(1)
  })

  it('serves dist files and SPA routes without allowing API or healthz fallback collisions', async () => {
    const distDir = await createStaticFixture({
      'assets/main-AbCdEf12.js': 'window.dashboardLoaded = true',
      'index.html': '<!doctype html><html><body><main id="root"></main></body></html>',
    })
    const { server, requests } = createTestServer(undefined, undefined, { distDir })
    const headers = trustedBrowserHeaders()

    const crossSiteStatic = await server.inject({
      method: 'GET',
      url: '/',
      headers: {
        host: 'localhost:4173',
        origin: 'http://attacker.example',
        'sec-fetch-site': 'cross-site',
      },
    })
    const index = await server.inject({ method: 'GET', url: '/', headers })
    const spaRoute = await server.inject({ method: 'GET', url: '/runs/current', headers })
    const asset = await server.inject({ method: 'GET', url: '/assets/main-AbCdEf12.js', headers })
    const missingAsset = await server.inject({ method: 'GET', url: '/assets/missing-ZyXwVu98.js', headers })
    const api = await server.inject({ method: 'GET', url: '/api/health', headers })
    const unknownApi = await server.inject({ method: 'GET', url: '/api/not-an-spa-route', headers })
    const healthz = await server.inject({ method: 'GET', url: '/healthz', headers })
    const healthzPost = await server.inject({ method: 'POST', url: '/healthz', headers })

    expect(crossSiteStatic.statusCode).toBe(403)
    expect(crossSiteStatic.body).not.toContain('<main id="root"></main>')
    expect(index.statusCode).toBe(200)
    expect(index.body).toContain('<main id="root"></main>')
    expect(index.headers['cache-control']).toBe('no-store')
    expect(spaRoute.statusCode).toBe(200)
    expect(spaRoute.body).toBe(index.body)
    expect(asset.statusCode).toBe(200)
    expect(asset.body).toBe('window.dashboardLoaded = true')
    expect(asset.headers['cache-control']).toBe('public, max-age=31536000, immutable')
    expect(asset.headers['content-type']).toBe('application/javascript; charset=utf-8')
    expect(missingAsset.statusCode).toBe(404)
    expect(missingAsset.body).not.toContain('<main id="root"></main>')
    expect(api.statusCode).toBe(200)
    expect(api.body).toBe(JSON.stringify({ status: 'UP' }))
    expect(api.headers['cache-control']).toBe('no-store')
    expect(unknownApi.statusCode).toBe(404)
    expect(unknownApi.body).not.toContain('<main id="root"></main>')
    expect(healthz.statusCode).toBe(200)
    expect(healthz.json()).toEqual({ status: 'ok' })
    expect(healthz.headers['cache-control']).toBe('no-store')
    expect(healthzPost.statusCode).toBe(415)
    expect(healthzPost.body).not.toContain('<main id="root"></main>')
    expect(requests).toHaveLength(1)

    for (const response of [index, spaRoute, asset, api, unknownApi, healthz]) {
      expectDashboardSecurityHeaders(response.headers)
    }
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
      expect(response.headers['content-type']).toBe('application/json; charset=utf-8')
      expect(response.headers.authorization).toBeUndefined()
      expect(response.headers.cookie).toBeUndefined()
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
    expect(rejectedGet.body).not.toContain(browserSuppliedToken)
    expect(rejectedPost.body).not.toContain(browserSuppliedToken)
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
      expect(response.statusCode).toBe(503)
      expect(response.json()).toEqual({ error: 'unavailable' })
      expect(response.body).not.toContain(serverLocalToken)
    }
    expect(requests).toHaveLength(2)
  })

  it('returns a stable unavailable error without exposing upstream failure details', async () => {
    const unavailableFetch: TestFetch = async () => {
      const error = new Error(`fetch failed for ${internalUpstreamUrl}?q=${sensitiveQuery}; body=${sensitiveBody}; token=${serverLocalToken}`)
      error.stack = `${syntheticStackMarker}\n${error.stack ?? ''}`
      throw error
    }
    const { server, requests } = createTestServer(undefined, unavailableFetch)

    const response = await server.inject({
      method: 'POST',
      url: '/api/search/semantic',
      headers: { 'content-type': 'application/json' },
      payload: JSON.stringify({ q: sensitiveBody }),
    })

    expect(response.statusCode).toBe(503)
    expectSanitizedUpstreamFailure(response.body, response.headers, 'unavailable')
    expect(requests).toHaveLength(1)
    expect(requests[0]?.init.redirect).toBe('manual')
  })

  it('aborts an approved request after 10 seconds without a real-time delay', async () => {
    vi.useFakeTimers()
    try {
      const signals: AbortSignal[] = []
      const timeoutFetch: TestFetch = async (_input, init) => {
        const signal = init?.signal
        if (signal === null || signal === undefined) {
          throw new Error('missing abort signal')
        }
        signals.push(signal)
        return new Promise<Response>((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(new Error(`aborted ${internalUpstreamUrl}`)), { once: true })
        })
      }
      const { server, requests } = createTestServer(undefined, timeoutFetch)

      const responsePromise = server.inject({
        method: 'GET',
        url: `/api/search/keyword?q=${sensitiveQuery}`,
      })
      await vi.advanceTimersByTimeAsync(0)
      await vi.advanceTimersByTimeAsync(UPSTREAM_TIMEOUT_MILLISECONDS)
      const response = await responsePromise

      expect(response.statusCode).toBe(504)
      expectSanitizedUpstreamFailure(response.body, response.headers, 'timeout')
      expect(signals[0]?.aborted).toBe(true)
      expect(requests[0]?.init.redirect).toBe('manual')
    } finally {
      vi.useRealTimers()
    }
  })

  it('aborts oversized Content-Length responses before forwarding any partial body', async () => {
    const signals: AbortSignal[] = []
    const contentLengthFetch: TestFetch = async (_input, init) => {
      const signal = init?.signal
      if (signal !== null && signal !== undefined) {
        signals.push(signal)
      }
      return new Response(`${partialResponseMarker}:${serverLocalToken}`, {
        headers: {
          'content-length': String(MAX_UPSTREAM_RESPONSE_BYTES + 1),
          'content-type': 'application/json',
        },
      })
    }
    const { server, requests } = createTestServer(undefined, contentLengthFetch)

    const response = await server.inject({
      method: 'POST',
      url: '/api/search/semantic',
      headers: { 'content-type': 'application/json' },
      payload: JSON.stringify({ q: sensitiveBody }),
    })

    expect(response.statusCode).toBe(502)
    expectSanitizedUpstreamFailure(response.body, response.headers, 'response_too_large')
    expect(response.body).not.toContain(partialResponseMarker)
    expect(signals[0]?.aborted).toBe(true)
    expect(requests).toHaveLength(1)
  })

  it('aborts chunked responses that exceed 10 MiB without forwarding a partial body', async () => {
    const signals: AbortSignal[] = []
    const chunkedFetch: TestFetch = async (_input, init) => {
      const signal = init?.signal
      if (signal !== null && signal !== undefined) {
        signals.push(signal)
      }
      return createChunkedResponse([
        new TextEncoder().encode(partialResponseMarker),
        new Uint8Array(MAX_UPSTREAM_RESPONSE_BYTES),
      ])
    }
    const { server, requests } = createTestServer(undefined, chunkedFetch)

    const response = await server.inject({ method: 'GET', url: `/api/search/keyword?q=${sensitiveQuery}` })

    expect(response.statusCode).toBe(502)
    expectSanitizedUpstreamFailure(response.body, response.headers, 'response_too_large')
    expect(response.body).not.toContain(partialResponseMarker)
    expect(signals[0]?.aborted).toBe(true)
    expect(requests).toHaveLength(1)
  })

  it('uses manual redirects and rejects every tested upstream 3xx response without leaking a Location value', async () => {
    const redirectStatuses = [300, 301, 302, 303, 307, 308]
    let statusIndex = 0
    const redirectFetch: TestFetch = async () => {
      const status = redirectStatuses[statusIndex]
      statusIndex += 1
      return new Response(partialResponseMarker, {
        status,
        headers: { location: `${internalUpstreamUrl}?q=${sensitiveQuery}` },
      })
    }
    const { server, requests } = createTestServer(undefined, redirectFetch)

    for (const status of redirectStatuses) {
      const response = await server.inject({ method: 'GET', url: `/api/search/keyword?q=${sensitiveQuery}` })

      expect(response.statusCode, `redirect status ${status}`).toBe(502)
      expectSanitizedUpstreamFailure(response.body, response.headers, 'forbidden_route')
      expect(response.body).not.toContain(partialResponseMarker)
    }

    expect(requests).toHaveLength(redirectStatuses.length)
    expect(requests.every(({ init }) => init.redirect === 'manual')).toBe(true)
  })

  function createTestServer(
    upstreamResponse: () => Response = () => new Response(JSON.stringify({ status: 'UP' }), {
      headers: { 'content-type': 'application/json' },
    }),
    fetchOverride?: TestFetch,
    serverOptions: TestServerOptions = {},
  ) {
    const requests: { init: RequestInit; url: URL }[] = []
    const server = createBffServer({
      ...serverOptions,
      localToken: serverLocalToken,
      upstreamOrigin: 'https://memory.example',
      fetch: async (input, init = {}) => {
        requests.push({ init, url: new URL(input.toString()) })
        return fetchOverride === undefined ? upstreamResponse() : fetchOverride(input, init)
      },
    })
    servers.push(server)
    return { server, requests }
  }

  async function createStaticFixture(files: Readonly<Record<string, string>>) {
    const distDir = await mkdtemp(join(tmpdir(), 'p2a-dashboard-dist-'))
    staticDirectories.push(distDir)
    await Promise.all(Object.entries(files).map(async ([path, content]) => {
      const filePath = join(distDir, path)
      await mkdir(dirname(filePath), { recursive: true })
      await writeFile(filePath, content, 'utf8')
    }))
    return distDir
  }

  function trustedBrowserHeaders() {
    return {
      host: 'localhost:4173',
      origin: 'http://localhost:4173',
      'sec-fetch-site': 'same-origin',
    }
  }

  function expectDashboardSecurityHeaders(headers: Record<string, string | string[] | undefined>) {
    expect(headers['content-security-policy']).toBe(expectedDashboardCsp)
    expect(headers['cross-origin-opener-policy']).toBe('same-origin')
    expect(headers['cross-origin-resource-policy']).toBe('same-origin')
    expect(headers['permissions-policy']).toBe('camera=(), geolocation=(), microphone=(), payment=(), usb=()')
    expect(headers['referrer-policy']).toBe('no-referrer')
    expect(headers['x-content-type-options']).toBe('nosniff')
    expect(headers['x-frame-options']).toBe('DENY')
  }

  function createChunkedResponse(chunks: readonly Uint8Array[]) {
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(chunk)
        }
        controller.close()
      },
    })
    return new Response(body, { headers: { 'content-type': 'application/json' } })
  }

  function expectSanitizedUpstreamFailure(body: string, headers: unknown, code: string) {
    expect(body).toBe(JSON.stringify({ error: code }))
    const publicResponse = `${body}${JSON.stringify(headers)}`
    for (const sensitiveValue of [
      browserSuppliedToken,
      internalUpstreamUrl,
      sensitiveBody,
      sensitiveQuery,
      serverLocalToken,
      syntheticStackMarker,
    ]) {
      expect(publicResponse).not.toContain(sensitiveValue)
    }
  }

  function browserCredentialHeaders() {
    return {
      authorization: `Bearer ${browserSuppliedToken}`,
      connection: 'keep-alive',
      cookie: `session=${browserSuppliedToken}`,
      host: 'localhost',
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
