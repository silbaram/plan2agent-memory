import {
  normalizePriorityDocumentSourcePath,
  priorityDocumentSources,
  type PriorityDocumentKind,
} from '../artifact-viewer/artifactPresentation'
import type { SearchItemBase } from '../data-access'

type SearchApprovalStatus = 'approved' | 'conflict' | 'draft' | 'pending' | 'unknown'

interface PrioritySearchDocumentCopy {
  readonly label: string
  readonly purpose: string
}

export interface PrioritySearchDocument {
  readonly approvalStatus: SearchApprovalStatus
  readonly gate: string
  readonly label: string
  readonly linkedWorkCount: number | null
  readonly purpose: string
}

export interface GroupedSearchResult<T extends SearchItemBase> {
  readonly item: T
  readonly priorityDocument: PrioritySearchDocument | null
  readonly serverIndex: number
}

export interface GroupedSearchResults<T extends SearchItemBase> {
  readonly priorityResults: readonly GroupedSearchResult<T>[]
  readonly supportingResults: readonly GroupedSearchResult<T>[]
}

const prioritySearchDocumentCopy: Readonly<Record<PriorityDocumentKind, PrioritySearchDocumentCopy>> = {
  final_review: {
    label: '최종 검토',
    purpose: '이번 반복의 최종 검토 결과를 기록합니다.',
  },
  implementation_plan: {
    label: '구현 계획',
    purpose: '승인된 구현 접근 방식을 설명합니다.',
  },
  product_spec: {
    label: '제품 명세',
    purpose: '이번 반복에서 승인된 제품 결과를 정의합니다.',
  },
  task_graph: {
    label: '작업 그래프',
    purpose: '의존성을 고려한 구현 작업을 정의합니다.',
  },
  visual_experience: {
    label: '시각 경험 명세',
    purpose: '이번 반복의 승인된 시각 경험을 정의합니다.',
  },
}

/**
 * This only changes the presentation order for a single server page. It never
 * removes, merges, scores, or requests search results.
 */
export function groupCurrentSearchResults<T extends SearchItemBase>(items: readonly T[]): GroupedSearchResults<T> {
  const results = items.map((item, serverIndex) => ({
    item,
    priorityDocument: priorityDocumentFor(item),
    serverIndex,
  }))

  return {
    priorityResults: results.filter((result) => result.priorityDocument !== null),
    supportingResults: results.filter((result) => result.priorityDocument === null),
  }
}

export function approvalStatusLabel(status: SearchApprovalStatus): string {
  switch (status) {
    case 'approved':
      return '승인됨'
    case 'conflict':
      return '충돌'
    case 'draft':
      return '초안'
    case 'pending':
      return '대기'
    case 'unknown':
      return '확인 불가'
  }
}

function priorityDocumentFor(item: SearchItemBase): PrioritySearchDocument | null {
  const sourcePath = canonicalPrioritySourcePath(item)
  if (sourcePath === null) {
    return null
  }

  const source = priorityDocumentSources.find((candidate) => candidate.sourcePath === sourcePath)
  if (source === undefined) {
    return null
  }

  const copy = prioritySearchDocumentCopy[source.canonicalKind]
  return {
    approvalStatus: approvalStatusFromMetadata(item.metadata),
    gate: source.gate,
    label: copy.label,
    linkedWorkCount: linkedWorkCountFromMetadata(item.metadata),
    purpose: copy.purpose,
  }
}

function canonicalPrioritySourcePath(item: SearchItemBase): string | null {
  if (item.sourcePath === null) {
    return null
  }

  const sourceIterationId = item.sourceIds.sourceIterationId
  if (sourceIterationId === null || sourceIterationId.trim().length === 0) {
    return item.sourcePath
  }
  return normalizePriorityDocumentSourcePath(item.sourcePath, sourceIterationId)
}

function approvalStatusFromMetadata(metadata: Readonly<Record<string, string>>): SearchApprovalStatus {
  switch (metadata.explicitStatus) {
    case 'approved':
    case 'conflict':
    case 'draft':
    case 'pending':
      return metadata.explicitStatus
    default:
      return 'unknown'
  }
}

function linkedWorkCountFromMetadata(metadata: Readonly<Record<string, string>>): number | null {
  const value = metadata.linkedWorkCount
  if (value === undefined || !/^\d+$/.test(value)) {
    return null
  }

  const count = Number(value)
  return Number.isSafeInteger(count) ? count : null
}
