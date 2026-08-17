import type { ArtifactType } from '../data-access'

export type SearchMode = 'keyword' | 'semantic' | 'hybrid'

export type SearchScope =
  | { readonly kind: 'all' }
  | { readonly kind: 'project'; readonly projectId: string }
  | { readonly kind: 'iteration'; readonly iterationId: string; readonly projectId: string }

export interface FusionSettings {
  readonly candidateLimit: number
  readonly rrfK: number
}

export interface SearchUrlState {
  readonly cursor: string | null
  readonly filter: ArtifactType | null
  readonly fusion: FusionSettings | null
  readonly mode: SearchMode
  readonly page: number
  readonly q: string
  readonly scope: SearchScope
}

export interface SearchFormState {
  readonly candidateLimit: string
  readonly filter: string
  readonly mode: SearchMode
  readonly projectId: string
  readonly q: string
  readonly rrfK: string
  readonly scopeKind: SearchScope['kind']
  readonly iterationId: string
}

export type SearchFormFieldName = 'q' | 'projectId' | 'iterationId' | 'candidateLimit' | 'rrfK'

const DEFAULT_PAGE = 1
const SEARCH_PAGE_SIZE = 20
const MAX_CURSOR_LENGTH = 4096
const MAX_TEXT_LENGTH = 500

export const artifactTypeOptions: readonly { readonly label: string; readonly value: ArtifactType }[] = [
  { label: '문서 스냅샷', value: 'DOCUMENT_SNAPSHOT' },
  { label: '작업 그래프', value: 'TASK_GRAPH' },
  { label: '작업', value: 'TASK' },
  { label: '실행 기록', value: 'RUN_RECORD' },
  { label: '제안', value: 'PROPOSAL' },
  { label: '문서 청크', value: 'DOCUMENT_CHUNK' },
]

export function parseSearchUrl(params: URLSearchParams): SearchUrlState {
  const mode = parseMode(params.get('mode'))
  const filter = parseArtifactType(params.get('filter'))
  const fusion = parseFusion(params.get('fusion'))
  const scope = parseScope(params)
  const q = parseText(params.get('q')) ?? ''
  const page = parsePage(params.get('page'))
  const cursor = parseCursor(params.get('cursor'))
  const stateWithoutCursor: SearchUrlState = {
    cursor: null,
    filter,
    fusion,
    mode,
    page,
    q,
    scope,
  }
  const cursorKey = params.get('cursorKey')

  if (cursor === null || cursorKey !== searchInputKey(stateWithoutCursor)) {
    return { ...stateWithoutCursor, page: DEFAULT_PAGE }
  }

  return { ...stateWithoutCursor, cursor }
}

export function serializeSearchUrl(state: SearchUrlState): string {
  const params = new URLSearchParams()
  if (state.q.length > 0) {
    params.set('q', state.q)
  }
  if (state.scope.kind !== 'all') {
    params.set('scope', state.scope.kind)
    params.set('projectId', state.scope.projectId)
    if (state.scope.kind === 'iteration') {
      params.set('iterationId', state.scope.iterationId)
    }
  }
  if (state.filter !== null) {
    params.set('filter', state.filter)
  }
  if (state.mode !== 'keyword') {
    params.set('mode', state.mode)
  }
  if (state.fusion !== null) {
    params.set('fusion', `${state.fusion.candidateLimit}:${state.fusion.rrfK}`)
  }
  if (state.cursor !== null) {
    params.set('cursor', state.cursor)
    params.set('cursorKey', searchInputKey(state))
    params.set('page', String(state.page))
  }

  const query = params.toString()
  return query.length === 0 ? '' : `?${query}`
}

export function searchInputKey(state: SearchUrlState): string {
  const source = [
    state.q,
    state.scope.kind,
    state.scope.kind === 'all' ? '' : state.scope.projectId,
    state.scope.kind === 'iteration' ? state.scope.iterationId : '',
    state.filter ?? '',
    state.mode,
    state.fusion?.candidateLimit ?? '',
    state.fusion?.rrfK ?? '',
  ].join('\u0000')
  let hash = 2166136261

  for (let index = 0; index < source.length; index += 1) {
    hash ^= source.charCodeAt(index)
    hash = Math.imul(hash, 16777619)
  }

  return `v1-${(hash >>> 0).toString(36)}`
}

export function formStateFromSearchUrl(state: SearchUrlState): SearchFormState {
  return {
    candidateLimit: state.fusion === null ? '' : String(state.fusion.candidateLimit),
    filter: state.filter ?? '',
    iterationId: state.scope.kind === 'iteration' ? state.scope.iterationId : '',
    mode: state.mode,
    projectId: state.scope.kind === 'all' ? '' : state.scope.projectId,
    q: state.q,
    rrfK: state.fusion === null ? '' : String(state.fusion.rrfK),
    scopeKind: state.scope.kind,
  }
}

export function searchUrlFromForm(form: SearchFormState): SearchUrlState | null {
  const q = parseText(form.q)
  if (q === null) {
    return null
  }

  const scope = scopeFromForm(form)
  if (scope === null) {
    return null
  }

  if (form.mode === 'hybrid') {
    const fusion = fusionFromForm(form.candidateLimit, form.rrfK)
    if (fusion === undefined) {
      return null
    }
    return {
      cursor: null,
      filter: parseArtifactType(form.filter),
      fusion,
      mode: form.mode,
      page: DEFAULT_PAGE,
      q,
      scope,
    }
  }

  return {
    cursor: null,
    filter: parseArtifactType(form.filter),
    fusion: null,
    mode: form.mode,
    page: DEFAULT_PAGE,
    q,
    scope,
  }
}

export function validationMessageForForm(form: SearchFormState): string | null {
  const invalidFields = validationFieldsForForm(form)
  if (invalidFields.includes('q')) {
    return '검색어를 입력하세요.'
  }
  if (invalidFields.includes('projectId') || invalidFields.includes('iterationId')) {
    return form.scopeKind === 'iteration'
      ? '이터레이션 범위에는 프로젝트와 이터레이션 ID가 모두 필요합니다.'
      : '프로젝트 범위에는 프로젝트 ID가 필요합니다.'
  }
  if (invalidFields.includes('candidateLimit') || invalidFields.includes('rrfK')) {
    return `혼합 검색의 후보 수는 ${SEARCH_PAGE_SIZE} 이상인 정수이고 RRF k는 양의 정수여야 합니다.`
  }
  return null
}

export function validationFieldsForForm(form: SearchFormState): readonly SearchFormFieldName[] {
  if (parseText(form.q) === null) {
    return ['q']
  }

  const projectId = parseText(form.projectId)
  const iterationId = parseText(form.iterationId)
  if (form.scopeKind === 'project' && projectId === null) {
    return ['projectId']
  }
  if (form.scopeKind === 'iteration' && (projectId === null || iterationId === null)) {
    return [
      ...(projectId === null ? ['projectId'] as const : []),
      ...(iterationId === null ? ['iterationId'] as const : []),
    ]
  }

  if (form.mode !== 'hybrid' || fusionFromForm(form.candidateLimit, form.rrfK) !== undefined) {
    return []
  }

  return [
    ...(isValidCandidateLimitText(form.candidateLimit) ? [] : ['candidateLimit'] as const),
    ...(isValidRrfKText(form.rrfK) ? [] : ['rrfK'] as const),
  ]
}

function parseMode(value: string | null): SearchMode {
  switch (value) {
    case 'semantic':
    case 'hybrid':
    case 'keyword':
      return value
    default:
      return 'keyword'
  }
}

function parseArtifactType(value: string | null): ArtifactType | null {
  switch (value) {
    case 'DOCUMENT_SNAPSHOT':
    case 'ITERATION':
    case 'PROJECT':
    case 'TASK_GRAPH':
    case 'TASK':
    case 'RUN_RECORD':
    case 'PROPOSAL':
    case 'DOCUMENT_CHUNK':
      return value
    default:
      return null
  }
}

function parseScope(params: URLSearchParams): SearchScope {
  const projectId = parseText(params.get('projectId'))
  const iterationId = parseText(params.get('iterationId'))

  switch (params.get('scope')) {
    case 'project':
      return projectId === null ? { kind: 'all' } : { kind: 'project', projectId }
    case 'iteration':
      return projectId === null || iterationId === null
        ? { kind: 'all' }
        : { kind: 'iteration', iterationId, projectId }
    default:
      return { kind: 'all' }
  }
}

function parseFusion(value: string | null): FusionSettings | null {
  if (value === null || value === 'default') {
    return null
  }

  const match = /^(\d+):(\d+)$/.exec(value)
  if (match === null) {
    return null
  }

  const candidateLimit = Number(match[1])
  const rrfK = Number(match[2])
  return isValidFusion(candidateLimit, rrfK) ? { candidateLimit, rrfK } : null
}

function fusionFromForm(candidateLimitText: string, rrfKText: string): FusionSettings | null | undefined {
  const candidateLimit = candidateLimitText.trim()
  const rrfK = rrfKText.trim()
  if (candidateLimit.length === 0 && rrfK.length === 0) {
    return null
  }
  if (!/^\d+$/.test(candidateLimit) || !/^\d+$/.test(rrfK)) {
    return undefined
  }

  const candidateLimitValue = Number(candidateLimit)
  const rrfKValue = Number(rrfK)
  return isValidFusion(candidateLimitValue, rrfKValue)
    ? { candidateLimit: candidateLimitValue, rrfK: rrfKValue }
    : undefined
}

function isValidCandidateLimitText(value: string) {
  const normalized = value.trim()
  return /^\d+$/.test(normalized) && isValidFusion(Number(normalized), 1)
}

function isValidRrfKText(value: string) {
  const normalized = value.trim()
  return /^\d+$/.test(normalized) && Number.isSafeInteger(Number(normalized)) && Number(normalized) > 0
}

function isValidFusion(candidateLimit: number, rrfK: number) {
  return Number.isSafeInteger(candidateLimit)
    && Number.isSafeInteger(rrfK)
    && candidateLimit >= SEARCH_PAGE_SIZE
    && rrfK > 0
}

function parseText(value: string | null): string | null {
  const normalized = value?.trim()
  return normalized === undefined || normalized.length === 0 || normalized.length > MAX_TEXT_LENGTH
    ? null
    : normalized
}

function parsePage(value: string | null): number {
  if (value === null || !/^\d+$/.test(value)) {
    return DEFAULT_PAGE
  }
  const page = Number(value)
  return Number.isSafeInteger(page) && page >= DEFAULT_PAGE ? page : DEFAULT_PAGE
}

function parseCursor(value: string | null): string | null {
  const cursor = value?.trim()
  return cursor === undefined || cursor.length === 0 || cursor.length > MAX_CURSOR_LENGTH ? null : cursor
}

function scopeFromForm(form: SearchFormState): SearchScope | null {
  const projectId = parseText(form.projectId)
  const iterationId = parseText(form.iterationId)
  switch (form.scopeKind) {
    case 'all':
      return { kind: 'all' }
    case 'project':
      return projectId === null ? null : { kind: 'project', projectId }
    case 'iteration':
      return projectId === null || iterationId === null ? null : { kind: 'iteration', iterationId, projectId }
  }
}
