import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest, type RawServerDefault } from 'fastify'
import { readFile, realpath, stat } from 'node:fs/promises'
import { extname, isAbsolute, relative, resolve } from 'node:path'
import { brotliDecompressSync, gunzipSync, inflateSync } from 'node:zlib'

export const MAX_DECOMPRESSED_BODY_BYTES = 64 * 1024
export const MAX_UPSTREAM_RESPONSE_BYTES = 10 * 1024 * 1024
export const UPSTREAM_TIMEOUT_MILLISECONDS = 10_000

const DEFAULT_UPSTREAM_ORIGIN = 'http://127.0.0.1:8080'
const MAX_REQUEST_TARGET_LENGTH = 8 * 1024
const MAX_PATH_LENGTH = 1024
const MAX_QUERY_LENGTH = 4 * 1024
const MAX_QUERY_VALUE_LENGTH = 2048
const MAX_QUERY_PARAMETERS = 32
const MAX_METADATA_FILTERS = 20
const LOCAL_TOKEN_HEADER_NAME = 'x-p2a-local-token'
const REDACTED_VALUE = '[redacted]'
const NO_STORE_CACHE_CONTROL = 'no-store'
const IMMUTABLE_ASSET_CACHE_CONTROL = 'public, max-age=31536000, immutable'
const DASHBOARD_CONTENT_SECURITY_POLICY = [
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

const STATIC_CONTENT_TYPES: Readonly<Record<string, string>> = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.ico': 'image/x-icon',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
}

const APPROVED_UPSTREAM_RESPONSE_CONTENT_TYPES = new Set([
  'application/json',
  'application/problem+json',
])

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SAFE_IDENTIFIER_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,255}$/
const CURSOR_PATTERN = /^[a-zA-Z0-9_-]+={0,2}$/
const ARTIFACT_TYPES = new Set([
  'PROJECT',
  'ITERATION',
  'DOCUMENT_SNAPSHOT',
  'TASK_GRAPH',
  'TASK',
  'RUN_RECORD',
  'PROPOSAL',
  'DOCUMENT_CHUNK',
])
const DASHBOARD_ARTIFACT_TYPES = new Set([
  'DOCUMENT_SNAPSHOT',
  'TASK_GRAPH',
  'TASK',
  'RUN_RECORD',
  'PROPOSAL',
])
const GRAPH_NODE_KINDS = new Set([
  'DECISION',
  'ASSUMPTION',
  'CLARIFYING_QUESTION',
  'EVIDENCE',
  'SPEC_SECTION',
  'DOCUMENT',
  'TASK',
  'RUN',
  'PROPOSAL',
])
const GRAPH_TRACE_DIRECTIONS = new Set(['UPSTREAM', 'DOWNSTREAM', 'BOTH'])
const METHOD_OVERRIDE_HEADERS = [
  'x-http-method-override',
  'x-http-method',
  'x-method-override',
]

type ProxyMethod = 'GET' | 'POST'
type QueryPairs = ReadonlyArray<readonly [string, string]>
type Validator = (value: string) => boolean
type ApprovedUpstreamHeaders = Record<string, string>
type UpstreamFailureCode = 'bff_backend_unavailable' | 'bff_forbidden_route' | 'bff_response_too_large' | 'bff_timeout'
type StaticAsset = {
  body: Buffer
  contentType: string
  path: string
}

const UPSTREAM_FAILURE_STATUS_CODES: Readonly<Record<UpstreamFailureCode, number>> = {
  bff_backend_unavailable: 503,
  bff_forbidden_route: 502,
  bff_response_too_large: 502,
  bff_timeout: 504,
}

interface QueryRule {
  maxOccurrences?: number
  required?: boolean
  maxLength?: number
  validate?: Validator
}

interface RouteDefinition {
  method: ProxyMethod
  fastifyPath: string
  pathPattern: RegExp
  query: Readonly<Record<string, QueryRule>>
  schema?: Record<string, unknown>
}

interface UpstreamResponse {
  body: Buffer
  response: Response
}

export interface BffOptions {
  distDir?: string
  fetch?: typeof fetch
  localToken: string
  upstreamOrigin?: string
}

class BffRequestError extends Error {
  constructor(
    readonly statusCode: number,
    message: string,
  ) {
    super(message)
  }
}

class BffUpstreamError extends Error {
  readonly statusCode: number

  constructor(readonly code: UpstreamFailureCode) {
    super(code)
    this.statusCode = UPSTREAM_FAILURE_STATUS_CODES[code]
  }
}

const uuidSchema = {
  type: 'string',
  pattern: '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
}

const semanticSearchSchema = searchSchema({})
const hybridSearchSchema = searchSchema({
  rrfK: { type: 'integer', minimum: 1, maximum: 1000 },
  candidateLimit: { type: 'integer', minimum: 1, maximum: 500 },
})

const queryRules = {
  limit: { validate: isPageLimit },
  cursor: { maxLength: MAX_QUERY_VALUE_LENGTH, validate: isCursor },
  projectId: { validate: isUuid },
  iterationId: { validate: isUuid },
  sourceProjectId: { maxLength: 256, validate: isSafeText },
  sourceIterationId: { maxLength: 256, validate: isSafeText },
  sourceDocumentId: { maxLength: 256, validate: isSafeText },
  sourceTaskGraphId: { maxLength: 256, validate: isSafeText },
  sourceTaskId: { maxLength: 256, validate: isSafeText },
  sourceRunId: { maxLength: 256, validate: isSafeText },
  artifactType: { validate: isArtifactType },
  artifactTypes: { maxLength: 32, maxOccurrences: DASHBOARD_ARTIFACT_TYPES.size, validate: isDashboardArtifactType },
  sourcePath: { maxLength: 1024, validate: isSafeSourcePath },
  taskId: { validate: isUuid },
  runId: { validate: isUuid },
  contentHash: { validate: isContentHash },
  sourceReferenceCanonicalServerId: { validate: isUuid },
  sourceReferenceUri: { maxLength: 2048, validate: isSafeText },
  q: { required: true, maxLength: 1024, validate: isSearchText },
  nodeKind: { validate: isGraphNodeKind },
  query: { maxLength: 1024, validate: isSearchText },
  naturalKey: { required: true, validate: isSafeIdentifier },
  direction: { validate: isGraphTraceDirection },
  maxDepth: { validate: isMaxDepth },
} satisfies Record<string, QueryRule>

const routes: readonly RouteDefinition[] = [
  {
    method: 'GET',
    fastifyPath: '/api/health',
    pathPattern: /^\/api\/health$/,
    query: {},
  },
  {
    method: 'GET',
    fastifyPath: '/api/projects',
    pathPattern: /^\/api\/projects$/,
    query: pickQueryRules('limit', 'cursor'),
  },
  {
    method: 'GET',
    fastifyPath: '/api/projects/:projectId/iterations',
    pathPattern: /^\/api\/projects\/(?<projectId>[0-9a-fA-F-]{36})\/iterations$/,
    query: pickQueryRules('limit', 'cursor'),
  },
  {
    method: 'GET',
    fastifyPath: '/api/artifacts',
    pathPattern: /^\/api\/artifacts$/,
    query: pickQueryRules(
      'projectId',
      'iterationId',
      'sourceProjectId',
      'sourceIterationId',
      'sourceDocumentId',
      'sourceTaskGraphId',
      'sourceTaskId',
      'sourceRunId',
      'artifactType',
      'artifactTypes',
      'sourcePath',
      'taskId',
      'runId',
      'contentHash',
      'sourceReferenceCanonicalServerId',
      'sourceReferenceUri',
      'limit',
      'cursor',
    ),
  },
  {
    method: 'GET',
    fastifyPath: '/api/artifacts/:artifactType/:artifactId',
    pathPattern: /^\/api\/artifacts\/(?<artifactType>[A-Z_]+)\/(?<artifactId>[0-9a-fA-F-]{36})$/,
    query: {},
  },
  {
    method: 'GET',
    fastifyPath: '/api/search/keyword',
    pathPattern: /^\/api\/search\/keyword$/,
    query: pickQueryRules('q', 'projectId', 'iterationId', 'artifactType', 'sourcePath', 'taskId', 'runId', 'limit', 'cursor'),
  },
  {
    method: 'GET',
    fastifyPath: '/api/graph/nodes',
    pathPattern: /^\/api\/graph\/nodes$/,
    query: pickQueryRules('projectId', 'iterationId', 'nodeKind', 'query', 'limit'),
  },
  {
    method: 'GET',
    fastifyPath: '/api/graph/trace',
    pathPattern: /^\/api\/graph\/trace$/,
    query: pickQueryRules('projectId', 'naturalKey', 'iterationId', 'direction', 'maxDepth'),
  },
  {
    method: 'POST',
    fastifyPath: '/api/search/semantic',
    pathPattern: /^\/api\/search\/semantic$/,
    query: {},
    schema: { body: semanticSearchSchema },
  },
  {
    method: 'POST',
    fastifyPath: '/api/search/hybrid',
    pathPattern: /^\/api\/search\/hybrid$/,
    query: {},
    schema: { body: hybridSearchSchema },
  },
]

export function createBffServer(options: BffOptions): FastifyInstance {
  const distDir = options.distDir
  const upstreamOrigin = resolveUpstreamOrigin(options.upstreamOrigin ?? DEFAULT_UPSTREAM_ORIGIN)
  const localToken = resolveLocalToken(options.localToken)
  const fetchImpl = options.fetch ?? globalThis.fetch

  if (typeof fetchImpl !== 'function') {
    throw new TypeError('A fetch implementation is required')
  }

  const server = Fastify<RawServerDefault>({
    bodyLimit: MAX_DECOMPRESSED_BODY_BYTES,
    exposeHeadRoutes: false,
    logger: false,
    rewriteUrl(request) {
      const requestUrl = request.url ?? '/__p2a_bff_rejected_request_target__'
      return requiresRoutingRejection(requestUrl) ? '/__p2a_bff_rejected_request_target__' : requestUrl
    },
    ajv: {
      customOptions: {
        removeAdditional: false,
      },
    },
  })

  server.addContentTypeParser(
    'application/json',
    { bodyLimit: MAX_DECOMPRESSED_BODY_BYTES, parseAs: 'buffer' },
    (request, body, done) => {
      try {
        const encodedBody = Buffer.isBuffer(body) ? body : Buffer.from(body)
        const decodedBody = decompressBody(headerValue(request.headers['content-encoding']), encodedBody)
        done(null, JSON.parse(decodedBody.toString('utf8')) as unknown)
      } catch (error) {
        done(error as Error)
      }
    },
  )

  server.addHook('onRequest', async (request, reply) => {
    const target = request.raw.url ?? request.url
    try {
      assertTrustedRequestContext(request, target)
      assertSafeRequestTarget(target)
      assertNoMethodOverride(request)
      assertBodyRequestShape(request)
    } catch (error) {
      return sendRequestError(reply, error)
    }
  })

  server.addHook('onSend', async (_request, reply, payload) => {
    setDashboardSecurityHeaders(reply)
    if (!reply.hasHeader('cache-control')) {
      reply.header('cache-control', NO_STORE_CACHE_CONTROL)
    }
    return payload
  })

  for (const route of routes) {
    server.route({
      method: route.method,
      url: route.fastifyPath,
      schema: route.schema,
      handler: async (request, reply) => {
        const target = validateRouteTarget(request.raw.url ?? request.url, route)

        if (route.method === 'POST') {
          validateSearchRequestBody(request.body, route.fastifyPath)
        }

        const upstreamUrl = buildUpstreamUrl(upstreamOrigin, target.path, target.query)
        const upstreamResponse = await fetchApprovedUpstream(
          fetchImpl,
          upstreamUrl,
          route.method,
          request.body,
          localToken,
        )
        const responseBody = redactLocalTokenFromResponse(upstreamResponse.body, localToken).toString('utf8')

        setApprovedUpstreamResponseHeaders(reply, upstreamResponse.response.headers)

        return reply.code(upstreamResponse.response.status).send(responseBody)
      },
    })
  }

  server.route({
    method: 'GET',
    url: '/healthz',
    handler: async (_request, reply) => reply.code(200).send({ status: 'ok' }),
  })

  server.setNotFoundHandler(async (request, reply) => {
    const target = request.raw.url ?? request.url
    const path = getRequestPath(target)
    if (request.method !== 'GET' || distDir === undefined || !isStaticRoute(path)) {
      return reply.code(404).send({ error: 'Not found' })
    }

    const staticAsset = await readStaticAsset(distDir, isStaticAssetPath(path) ? path : '/index.html')
    if (staticAsset === undefined) {
      return reply.code(404).send({ error: 'Not found' })
    }

    reply.header('cache-control', staticAssetCacheControl(staticAsset.path))
    reply.header('content-type', staticAsset.contentType)
    return reply.code(200).send(staticAsset.body)
  })
  server.setErrorHandler((error, _request, reply) => {
    if (error instanceof BffRequestError) {
      return sendRequestError(reply, error)
    }
    if (error instanceof BffUpstreamError) {
      return reply.code(error.statusCode).send({ error: error.code })
    }
    const standardError = toStandardError(error)

    if (standardError.code === 'FST_ERR_CTP_BODY_TOO_LARGE') {
      return reply.code(413).send({ error: 'Request body is too large' })
    }

    if (standardError.validation !== undefined) {
      return reply.code(400).send({ error: 'Invalid request body' })
    }

    return reply.code(500).send({ error: 'Upstream request failed' })
  })

  return server
}

export function isAllowedBffRequestTarget(target: string) {
  return !requiresRoutingRejection(target)
}

function assertTrustedRequestContext(request: FastifyRequest, target: string) {
  const loopbackAuthority = getLoopbackAuthority(headerValue(request.headers.host))
  if (loopbackAuthority === undefined) {
    throw new BffRequestError(403, 'Forbidden request context')
  }

  const origin = headerValue(request.headers.origin)
  if (origin !== undefined && !isSameLoopbackOrigin(origin, loopbackAuthority, request.protocol)) {
    throw new BffRequestError(403, 'Forbidden request context')
  }

  const fetchSite = headerValue(request.headers['sec-fetch-site'])
  if (fetchSite !== undefined && !isAllowedFetchSite(fetchSite, getRequestPath(target))) {
    throw new BffRequestError(403, 'Forbidden request context')
  }
}

function getLoopbackAuthority(host: string | undefined) {
  if (host === undefined) {
    return undefined
  }

  try {
    const url = new URL(`http://${host}`)
    if (url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
      return undefined
    }

    const hostname = url.hostname.toLowerCase()
    if (hostname !== 'localhost' && hostname !== '127.0.0.1' && hostname !== '[::1]' && hostname !== '::1') {
      return undefined
    }
    return url.host.toLowerCase()
  } catch {
    return undefined
  }
}

function isSameLoopbackOrigin(origin: string, expectedAuthority: string, protocol: string) {
  try {
    const url = new URL(origin)
    if (
      url.protocol !== `${protocol}:`
      || url.username
      || url.password
      || url.pathname !== '/'
      || url.search
      || url.hash
    ) {
      return false
    }
    return getLoopbackAuthority(url.host) === expectedAuthority
  } catch {
    return false
  }
}

function isAllowedFetchSite(value: string, path: string) {
  const site = value.trim().toLowerCase()
  if (site === 'same-origin') {
    return true
  }
  return site === 'none' && !isApiOrHealthPath(path)
}

function getRequestPath(target: string) {
  const queryIndex = target.indexOf('?')
  return queryIndex === -1 ? target : target.slice(0, queryIndex)
}

function isApiOrHealthPath(path: string) {
  return path === '/api' || path.startsWith('/api/') || path === '/healthz'
}

function isStaticRoute(path: string) {
  return !isApiOrHealthPath(path)
}

function isStaticAssetPath(path: string) {
  const filename = path.slice(path.lastIndexOf('/') + 1)
  return filename.includes('.')
}

async function readStaticAsset(distDir: string, requestPath: string): Promise<StaticAsset | undefined> {
  try {
    const staticRoot = await realpath(distDir)
    const candidate = resolve(staticRoot, requestPath.slice(1))
    if (!isWithinDirectory(staticRoot, candidate)) {
      return undefined
    }

    const realCandidate = await realpath(candidate)
    if (!isWithinDirectory(staticRoot, realCandidate)) {
      return undefined
    }

    const file = await stat(realCandidate)
    if (!file.isFile()) {
      return undefined
    }

    return {
      body: await readFile(realCandidate),
      contentType: staticContentType(requestPath),
      path: requestPath,
    }
  } catch {
    return undefined
  }
}

function isWithinDirectory(root: string, candidate: string) {
  const pathFromRoot = relative(root, candidate)
  return pathFromRoot === '' || (!pathFromRoot.startsWith('..') && !isAbsolute(pathFromRoot))
}

function staticContentType(path: string) {
  return STATIC_CONTENT_TYPES[extname(path).toLowerCase()] ?? 'application/octet-stream'
}

function staticAssetCacheControl(path: string) {
  return /-[A-Za-z0-9_-]{8,}\.[^/]+$/.test(path)
    ? IMMUTABLE_ASSET_CACHE_CONTROL
    : NO_STORE_CACHE_CONTROL
}

function setDashboardSecurityHeaders(reply: FastifyReply) {
  reply.header('content-security-policy', DASHBOARD_CONTENT_SECURITY_POLICY)
  reply.header('cross-origin-opener-policy', 'same-origin')
  reply.header('cross-origin-resource-policy', 'same-origin')
  reply.header('permissions-policy', 'camera=(), geolocation=(), microphone=(), payment=(), usb=()')
  reply.header('referrer-policy', 'no-referrer')
  reply.header('x-content-type-options', 'nosniff')
  reply.header('x-frame-options', 'DENY')
}

function searchSchema(extraProperties: Record<string, unknown>) {
  return {
    type: 'object',
    additionalProperties: false,
    required: ['q'],
    properties: {
      q: { type: 'string', minLength: 1, maxLength: 1024 },
      projectId: uuidSchema,
      iterationId: uuidSchema,
      artifactType: enumSchema([...ARTIFACT_TYPES]),
      sourcePath: safeTextSchema(1024),
      taskId: uuidSchema,
      runId: uuidSchema,
      metadataFilters: {
        type: 'object',
        maxProperties: MAX_METADATA_FILTERS,
        additionalProperties: { type: 'string', minLength: 1, maxLength: 256 },
        propertyNames: { pattern: '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$' },
      },
      limit: { type: 'integer', minimum: 1, maximum: 200 },
      cursor: { type: 'string', minLength: 1, maxLength: MAX_QUERY_VALUE_LENGTH, pattern: '^[A-Za-z0-9_-]+={0,2}$' },
      ...extraProperties,
    },
  }
}

function enumSchema(values: readonly string[]) {
  return { type: 'string', enum: values }
}

function safeTextSchema(maxLength: number) {
  return { type: 'string', minLength: 1, maxLength }
}

function pickQueryRules(...keys: (keyof typeof queryRules)[]) {
  return Object.fromEntries(keys.map((key) => [key, queryRules[key]]))
}

function assertSafeRequestTarget(target: string) {
  if (target.length > MAX_REQUEST_TARGET_LENGTH) {
    throw new BffRequestError(414, 'Request target is too long')
  }

  const queryIndex = target.indexOf('?')
  const path = queryIndex === -1 ? target : target.slice(0, queryIndex)
  const query = queryIndex === -1 ? '' : target.slice(queryIndex + 1)

  if (!path.startsWith('/') || path.length > MAX_PATH_LENGTH || query.length > MAX_QUERY_LENGTH) {
    throw new BffRequestError(400, 'Invalid request target')
  }

  if (path.includes('\\') || path.includes('//') || path.includes('#')) {
    throw new BffRequestError(400, 'Unsafe request path')
  }

  assertValidPercentEncoding(path, 'request path')
  let decodedPath: string
  try {
    decodedPath = decodeURIComponent(path)
  } catch {
    throw new BffRequestError(400, 'Malformed request path encoding')
  }
  if (decodedPath !== path || decodedPath.includes('\\') || hasTraversalSegment(decodedPath)) {
    throw new BffRequestError(400, 'Encoded or traversal request path is not allowed')
  }

  if (query.includes('#')) {
    throw new BffRequestError(400, 'Invalid request target')
  }
}

function requiresRoutingRejection(target: string) {
  const queryIndex = target.indexOf('?')
  const path = queryIndex === -1 ? target : target.slice(0, queryIndex)
  if (!path.startsWith('/') || path.includes('\\') || path.includes('//') || path.includes('#')) {
    return true
  }

  try {
    assertValidPercentEncoding(path, 'request path')
    const decodedPath = decodeURIComponent(path)
    return decodedPath !== path || decodedPath.includes('\\') || hasTraversalSegment(decodedPath)
  } catch {
    return true
  }
}

function assertNoMethodOverride(request: FastifyRequest) {
  if (METHOD_OVERRIDE_HEADERS.some((header) => request.headers[header] !== undefined)) {
    throw new BffRequestError(400, 'Method overrides are not allowed')
  }
}

function assertBodyRequestShape(request: FastifyRequest) {
  const declaredLength = headerValue(request.headers['content-length'])
  const contentLength = declaredLength === undefined ? undefined : Number(declaredLength)
  const hasTransferEncoding = request.headers['transfer-encoding'] !== undefined
  if (contentLength !== undefined && (!Number.isSafeInteger(contentLength) || contentLength < 0)) {
    throw new BffRequestError(400, 'Invalid Content-Length header')
  }

  if (contentLength !== undefined && contentLength > MAX_DECOMPRESSED_BODY_BYTES) {
    throw new BffRequestError(413, 'Request body is too large')
  }

  if (request.method === 'POST') {
    const contentType = headerValue(request.headers['content-type'])
    if (contentType === undefined || contentType.split(';', 1)[0].trim().toLowerCase() !== 'application/json') {
      throw new BffRequestError(415, 'Only application/json request bodies are allowed')
    }
    return
  }

  if ((contentLength !== undefined && contentLength > 0) || hasTransferEncoding) {
    throw new BffRequestError(400, 'GET requests must not include a body')
  }
}

function validateRouteTarget(target: string, route: RouteDefinition) {
  assertSafeRequestTarget(target)
  const queryIndex = target.indexOf('?')
  const path = queryIndex === -1 ? target : target.slice(0, queryIndex)
  const rawQuery = queryIndex === -1 ? '' : target.slice(queryIndex + 1)
  const match = route.pathPattern.exec(path)

  if (match === null) {
    throw new BffRequestError(404, 'Route is not allowed')
  }

  const parameters = match.groups ?? {}
  if (parameters.projectId !== undefined && !isUuid(parameters.projectId)) {
    throw new BffRequestError(400, 'Invalid project identifier')
  }
  if (parameters.artifactType !== undefined && !DASHBOARD_ARTIFACT_TYPES.has(parameters.artifactType)) {
    throw new BffRequestError(400, 'Invalid artifact type')
  }
  if (parameters.artifactId !== undefined && !isUuid(parameters.artifactId)) {
    throw new BffRequestError(400, 'Invalid artifact identifier')
  }

  return { path, query: validateQuery(rawQuery, route.query) }
}

function validateQuery(rawQuery: string, rules: Readonly<Record<string, QueryRule>>): QueryPairs {
  if (rawQuery.length === 0) {
    assertRequiredQueryParameters(new Set(), rules)
    return []
  }

  const pairs = rawQuery.split('&')
  if (pairs.length > MAX_QUERY_PARAMETERS) {
    throw new BffRequestError(400, 'Too many query parameters')
  }

  const values: [string, string][] = []
  const occurrences = new Map<string, number>()
  for (const pair of pairs) {
    if (pair.length === 0) {
      throw new BffRequestError(400, 'Empty query parameter is not allowed')
    }
    const separator = pair.indexOf('=')
    const rawName = separator === -1 ? pair : pair.slice(0, separator)
    const rawValue = separator === -1 ? '' : pair.slice(separator + 1)
    const name = decodeQueryComponent(rawName)
    const value = decodeQueryComponent(rawValue)
    const rule = rules[name]

    const occurrenceCount = occurrences.get(name) ?? 0
    const maximumOccurrences = rule?.maxOccurrences ?? 1
    if (rule === undefined || occurrenceCount >= maximumOccurrences) {
      throw new BffRequestError(400, 'Query parameter is not allowed')
    }
    if (value.length === 0 || value.length > (rule.maxLength ?? MAX_QUERY_VALUE_LENGTH) || !isSafeQueryValue(value)) {
      throw new BffRequestError(400, 'Invalid query parameter value')
    }
    if (rule.validate !== undefined && !rule.validate(value)) {
      throw new BffRequestError(400, 'Invalid query parameter value')
    }

    occurrences.set(name, occurrenceCount + 1)
    values.push([name, value])
  }

  assertRequiredQueryParameters(new Set(occurrences.keys()), rules)
  return values
}

function assertRequiredQueryParameters(seen: ReadonlySet<string>, rules: Readonly<Record<string, QueryRule>>) {
  for (const [name, rule] of Object.entries(rules)) {
    if (rule.required && !seen.has(name)) {
      throw new BffRequestError(400, `Missing required query parameter: ${name}`)
    }
  }
}

function decodeQueryComponent(value: string): string {
  assertValidPercentEncoding(value, 'query parameter')
  try {
    return decodeURIComponent(value.replace(/\+/g, ' '))
  } catch {
    throw new BffRequestError(400, 'Malformed query encoding')
  }
}

function assertValidPercentEncoding(value: string, label: string) {
  for (let index = value.indexOf('%'); index !== -1; index = value.indexOf('%', index + 1)) {
    if (!/^[0-9a-fA-F]{2}$/.test(value.slice(index + 1, index + 3))) {
      throw new BffRequestError(400, `Malformed ${label} encoding`)
    }
  }
}

function buildUpstreamUrl(origin: string, path: string, query: QueryPairs) {
  const url = new URL(path, origin)
  for (const [name, value] of query) {
    url.searchParams.append(name, value)
  }
  return url
}

async function fetchApprovedUpstream(
  fetchImpl: typeof fetch,
  upstreamUrl: URL,
  method: ProxyMethod,
  body: unknown,
  localToken: string,
): Promise<UpstreamResponse> {
  const abortController = new AbortController()
  const timeout = setTimeout(() => abortController.abort(), UPSTREAM_TIMEOUT_MILLISECONDS)

  try {
    const response = await fetchImpl(
      upstreamUrl,
      buildApprovedUpstreamRequest(method, body, localToken, abortController.signal),
    )
    assertNoUpstreamRedirect(response, abortController)
    const responseBody = await readBoundedUpstreamResponse(response, abortController)
    return { body: responseBody, response }
  } catch (error) {
    if (error instanceof BffUpstreamError) {
      throw error
    }
    if (abortController.signal.aborted) {
      throw new BffUpstreamError('bff_timeout')
    }
    throw new BffUpstreamError('bff_backend_unavailable')
  } finally {
    clearTimeout(timeout)
  }
}

function buildApprovedUpstreamRequest(
  method: ProxyMethod,
  body: unknown,
  localToken: string,
  signal: AbortSignal,
): RequestInit {
  return {
    method,
    headers: buildApprovedUpstreamHeaders(method, localToken),
    body: method === 'POST' ? JSON.stringify(body) : undefined,
    redirect: 'manual',
    signal,
  }
}

function buildApprovedUpstreamHeaders(method: ProxyMethod, localToken: string): ApprovedUpstreamHeaders {
  const headers: ApprovedUpstreamHeaders = {
    accept: 'application/json',
    [LOCAL_TOKEN_HEADER_NAME]: localToken,
  }
  if (method === 'POST') {
    headers['content-type'] = 'application/json'
  }
  return headers
}

function assertNoUpstreamRedirect(response: Response, abortController: AbortController) {
  if (response.status >= 300 && response.status < 400) {
    abortController.abort()
    throw new BffUpstreamError('bff_forbidden_route')
  }
}

async function readBoundedUpstreamResponse(response: Response, abortController: AbortController): Promise<Buffer> {
  if (getUpstreamContentLength(response) > MAX_UPSTREAM_RESPONSE_BYTES) {
    abortController.abort()
    throw new BffUpstreamError('bff_response_too_large')
  }

  if (response.body === null) {
    return Buffer.alloc(0)
  }

  const reader = response.body.getReader()
  const chunks: Buffer[] = []
  let totalLength = 0
  try {
    while (true) {
      const result = await reader.read()
      if (result.done) {
        break
      }

      const chunk = Buffer.from(result.value)
      totalLength += chunk.length
      if (totalLength > MAX_UPSTREAM_RESPONSE_BYTES) {
        abortController.abort()
        void reader.cancel().catch(() => undefined)
        throw new BffUpstreamError('bff_response_too_large')
      }
      chunks.push(chunk)
    }
  } finally {
    reader.releaseLock()
  }

  return Buffer.concat(chunks, totalLength)
}

function getUpstreamContentLength(response: Response) {
  const contentLength = response.headers.get('content-length')
  if (contentLength === null || !/^[0-9]+$/.test(contentLength)) {
    return 0
  }
  return Number(contentLength)
}

function setApprovedUpstreamResponseHeaders(reply: FastifyReply, headers: Headers) {
  const contentType = normalizeApprovedResponseContentType(headers.get('content-type'))
  if (contentType !== undefined) {
    reply.header('content-type', contentType)
  }
}

function normalizeApprovedResponseContentType(value: string | null) {
  if (value === null) {
    return undefined
  }

  const [rawMediaType, ...rawParameters] = value.split(';')
  const mediaType = rawMediaType?.trim().toLowerCase()
  if (mediaType === undefined || !APPROVED_UPSTREAM_RESPONSE_CONTENT_TYPES.has(mediaType)) {
    return undefined
  }

  return rawParameters.length === 1 && rawParameters[0]?.trim().toLowerCase() === 'charset=utf-8'
    ? `${mediaType}; charset=utf-8`
    : mediaType
}

function redactLocalTokenFromResponse(body: Buffer, localToken: string) {
  const tokenBytes = Buffer.from(localToken, 'utf8')
  const firstMatch = body.indexOf(tokenBytes)
  if (firstMatch === -1) {
    return body
  }

  const redactedValue = Buffer.from(REDACTED_VALUE, 'utf8')
  const parts: Buffer[] = []
  let start = 0
  let match = firstMatch
  while (match !== -1) {
    parts.push(body.subarray(start, match), redactedValue)
    start = match + tokenBytes.length
    match = body.indexOf(tokenBytes, start)
  }
  parts.push(body.subarray(start))
  return Buffer.concat(parts)
}

function decompressBody(contentEncoding: string | undefined, body: Buffer) {
  const encoding = contentEncoding?.trim().toLowerCase() ?? 'identity'
  let decoded: Buffer
  try {
    switch (encoding) {
      case 'identity':
        decoded = body
        break
      case 'gzip':
        decoded = gunzipSync(body, { maxOutputLength: MAX_DECOMPRESSED_BODY_BYTES })
        break
      case 'deflate':
        decoded = inflateSync(body, { maxOutputLength: MAX_DECOMPRESSED_BODY_BYTES })
        break
      case 'br':
        decoded = brotliDecompressSync(body, { maxOutputLength: MAX_DECOMPRESSED_BODY_BYTES })
        break
      default:
        throw new BffRequestError(415, 'Unsupported Content-Encoding')
    }
  } catch (error) {
    if (error instanceof BffRequestError) {
      throw error
    }
    if (isBodyLimitError(error)) {
      throw new BffRequestError(413, 'Request body is too large')
    }
    throw new BffRequestError(400, 'Invalid compressed JSON body')
  }

  if (decoded.length > MAX_DECOMPRESSED_BODY_BYTES) {
    throw new BffRequestError(413, 'Request body is too large')
  }
  return decoded
}

function isBodyLimitError(error: unknown) {
  return typeof error === 'object' && error !== null && 'code' in error && error.code === 'ERR_BUFFER_TOO_LARGE'
}

function validateSearchRequestBody(body: unknown, path: string) {
  if (!isRecord(body)) {
    throw new BffRequestError(400, 'Invalid request body')
  }
  const query = body.q
  if (!isSearchText(query) || query.length > 1024) {
    throw new BffRequestError(400, 'Invalid search query')
  }
  if (body.projectId !== undefined && !isStringMatching(body.projectId, isUuid)) {
    throw new BffRequestError(400, 'Invalid project identifier')
  }
  if (body.iterationId !== undefined && !isStringMatching(body.iterationId, isUuid)) {
    throw new BffRequestError(400, 'Invalid iteration identifier')
  }
  if (body.artifactType !== undefined && !isStringMatching(body.artifactType, isArtifactType)) {
    throw new BffRequestError(400, 'Invalid artifact type')
  }
  if (body.sourcePath !== undefined && !isStringMatching(body.sourcePath, isSafeSourcePath)) {
    throw new BffRequestError(400, 'Invalid source path')
  }
  if (body.taskId !== undefined && !isStringMatching(body.taskId, isUuid)) {
    throw new BffRequestError(400, 'Invalid task identifier')
  }
  if (body.runId !== undefined && !isStringMatching(body.runId, isUuid)) {
    throw new BffRequestError(400, 'Invalid run identifier')
  }
  if (body.cursor !== undefined && !isStringMatching(body.cursor, isCursor)) {
    throw new BffRequestError(400, 'Invalid cursor')
  }
  if (body.metadataFilters !== undefined && !isValidMetadataFilters(body.metadataFilters)) {
    throw new BffRequestError(400, 'Invalid metadata filters')
  }
  if (
    path === '/api/search/hybrid'
    && typeof body.candidateLimit === 'number'
    && typeof body.limit === 'number'
    && body.candidateLimit < body.limit
  ) {
    throw new BffRequestError(400, 'candidateLimit must be greater than or equal to limit')
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isValidMetadataFilters(value: unknown) {
  if (!isRecord(value) || Object.keys(value).length > MAX_METADATA_FILTERS) {
    return false
  }
  return Object.entries(value).every(([key, entry]) => (
    SAFE_IDENTIFIER_PATTERN.test(key)
    && key.length <= 64
    && typeof entry === 'string'
    && isSafeText(entry)
    && entry.length <= 256
  ))
}

function isStringMatching(value: unknown, validator: Validator): value is string {
  return typeof value === 'string' && validator(value)
}

function isUuid(value: string) {
  return UUID_PATTERN.test(value)
}

function isPageLimit(value: string) {
  return isIntegerInRange(value, 1, 200)
}

function isMaxDepth(value: string) {
  return isIntegerInRange(value, 1, 10)
}

function isIntegerInRange(value: string, min: number, max: number) {
  return /^(?:0|[1-9][0-9]*)$/.test(value) && Number(value) >= min && Number(value) <= max
}

function isCursor(value: string) {
  return value.length <= MAX_QUERY_VALUE_LENGTH && CURSOR_PATTERN.test(value)
}

function isArtifactType(value: string) {
  return ARTIFACT_TYPES.has(value)
}

function isDashboardArtifactType(value: string) {
  return DASHBOARD_ARTIFACT_TYPES.has(value)
}

function isGraphNodeKind(value: string) {
  return GRAPH_NODE_KINDS.has(value)
}

function isGraphTraceDirection(value: string) {
  return GRAPH_TRACE_DIRECTIONS.has(value)
}

function isContentHash(value: string) {
  return /^[0-9a-f]{64}$/i.test(value)
}

function isSafeIdentifier(value: string) {
  return SAFE_IDENTIFIER_PATTERN.test(value)
}

function isSafeText(value: string) {
  return value.length > 0 && !value.includes('\\') && !containsControlCharacter(value)
}

function isSearchText(value: unknown): value is string {
  return typeof value === 'string' && isSafeText(value) && value.trim().length > 0
}

function isSafeSourcePath(value: string) {
  return isSafeText(value) && !hasTraversalSegment(value)
}

function isSafeQueryValue(value: string) {
  return isSafeText(value) && !hasTraversalSegment(value)
}

function hasTraversalSegment(value: string) {
  return value.split('/').some((segment) => segment === '.' || segment === '..')
}

function containsControlCharacter(value: string) {
  return [...value].some((character) => character.codePointAt(0)! < 32)
}

function resolveUpstreamOrigin(value: string) {
  const url = new URL(value)
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new TypeError('upstreamOrigin must be an absolute HTTP(S) origin without a path')
  }
  return url.origin
}

function resolveLocalToken(value: string) {
  if (!/^[\x21-\x7e]+$/.test(value)) {
    throw new TypeError('localToken must be a non-empty header-safe value')
  }
  return value
}

function headerValue(value: string | string[] | undefined) {
  return typeof value === 'string' ? value : undefined
}

function sendRequestError(reply: FastifyReply, error: unknown) {
  const requestError = error instanceof BffRequestError
    ? error
    : new BffRequestError(400, 'Invalid request')
  return reply.code(requestError.statusCode).send({ error: requestError.message })
}

function toStandardError(error: unknown) {
  return isRecord(error)
    ? {
      code: error.code,
      validation: error.validation,
    }
    : {}
}
