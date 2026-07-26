import { useInfiniteQuery, type InfiniteData } from '@tanstack/react-query'
import { useId, useMemo, useState, type KeyboardEvent, type MouseEvent, type ReactNode } from 'react'
import { dashboardApi } from '../data-access'
import type {
  ArtifactLookupItem,
  DashboardApiClient,
  DashboardArtifactType,
  IterationSummary,
  Page,
  ProjectSummary,
} from '../data-access'
import './artifactTree.css'

const TREE_ARTIFACT_TYPES: readonly DashboardArtifactType[] = [
  'DOCUMENT_SNAPSHOT',
  'TASK_GRAPH',
  'TASK',
  'RUN_RECORD',
  'PROPOSAL',
]

const DEFAULT_PAGE_SIZE = 50

export interface ArtifactTreeSelection {
  readonly artifactId: string
  readonly artifactType: DashboardArtifactType
  readonly iterationId: string | null
  readonly projectId: string
}

export interface ArtifactTreeProps {
  readonly apiClient?: DashboardApiClient
  readonly ariaLabel?: string
  readonly onArtifactSelect?: (selection: ArtifactTreeSelection) => void
  readonly pageSize?: number
  readonly selectedArtifact?: ArtifactTreeSelection | null
}

interface TreeNodeProps {
  readonly children?: ReactNode
  readonly expanded?: boolean
  readonly label: string
  readonly level: number
  readonly onActivate: () => void
  readonly selectable?: boolean
  readonly selected?: boolean
}

interface TreePageProps<T> {
  readonly emptyDescription: string
  readonly errorDescription: string
  readonly errorTitle: string
  readonly hasNextPage: boolean
  readonly isError: boolean
  readonly isFetchingNextPage: boolean
  readonly isPending: boolean
  readonly items: readonly T[]
  readonly loadingLabel: string
  readonly onLoadMore: () => void
  readonly onRetry: () => void
  readonly renderItem: (item: T) => ReactNode
}

export function ArtifactTree({
  apiClient = dashboardApi,
  ariaLabel = '산출물 계층',
  onArtifactSelect,
  pageSize = DEFAULT_PAGE_SIZE,
  selectedArtifact,
}: ArtifactTreeProps) {
  const headingId = useId()
  const [uncontrolledSelection, setUncontrolledSelection] = useState<ArtifactTreeSelection | null>(null)
  const effectivePageSize = normalizePageSize(pageSize)
  const selection = selectedArtifact === undefined ? uncontrolledSelection : selectedArtifact
  const projects = useTreePages(
    ['artifact-tree', 'projects', effectivePageSize],
    (cursor, signal) => apiClient.listProjects({ cursor, limit: effectivePageSize }, { signal }),
  )
  const projectItems = useUniqueItems(projects.data?.pages, (project) => project.projectId)

  function handleArtifactSelect(nextSelection: ArtifactTreeSelection) {
    if (selectedArtifact === undefined) {
      setUncontrolledSelection(nextSelection)
    }
    onArtifactSelect?.(nextSelection)
  }

  return (
    <section aria-labelledby={headingId} className="artifact-tree">
      <h2 className="artifact-tree__heading" id={headingId}>
        {ariaLabel}
      </h2>
      <div aria-label={ariaLabel} className="artifact-tree__root" onKeyDown={handleTreeKeyDown} role="tree">
        <TreePage
          emptyDescription="표시할 프로젝트가 없습니다."
          errorDescription="프로젝트 목록을 다시 불러오세요."
          errorTitle="프로젝트 목록을 불러올 수 없습니다"
          hasNextPage={projects.hasNextPage}
          isError={projects.isError}
          isFetchingNextPage={projects.isFetchingNextPage}
          isPending={projects.isPending}
          items={projectItems}
          loadingLabel="프로젝트 목록을 불러오는 중"
          onLoadMore={() => {
            void projects.fetchNextPage()
          }}
          onRetry={() => {
            void projects.refetch()
          }}
          renderItem={(project) => (
            <ProjectNode
              apiClient={apiClient}
              key={project.projectId}
              onArtifactSelect={handleArtifactSelect}
              pageSize={effectivePageSize}
              project={project}
              selectedArtifact={selection}
            />
          )}
        />
      </div>
    </section>
  )
}

interface ProjectNodeProps {
  readonly apiClient: DashboardApiClient
  readonly onArtifactSelect: (selection: ArtifactTreeSelection) => void
  readonly pageSize: number
  readonly project: ProjectSummary
  readonly selectedArtifact: ArtifactTreeSelection | null
}

function ProjectNode({ apiClient, onArtifactSelect, pageSize, project, selectedArtifact }: ProjectNodeProps) {
  const [expanded, setExpanded] = useState(false)

  return (
    <TreeNode
      expanded={expanded}
      label={`프로젝트 ${project.name}`}
      level={1}
      onActivate={() => {
        setExpanded((current) => !current)
      }}
    >
      <span className="artifact-tree__type" aria-hidden="true">
        프로젝트
      </span>
      <span className="artifact-tree__label">{project.name}</span>
      {expanded ? (
        <ProjectIterations
          apiClient={apiClient}
          onArtifactSelect={onArtifactSelect}
          pageSize={pageSize}
          projectId={project.projectId}
          selectedArtifact={selectedArtifact}
        />
      ) : null}
    </TreeNode>
  )
}

interface ProjectIterationsProps {
  readonly apiClient: DashboardApiClient
  readonly onArtifactSelect: (selection: ArtifactTreeSelection) => void
  readonly pageSize: number
  readonly projectId: string
  readonly selectedArtifact: ArtifactTreeSelection | null
}

function ProjectIterations({ apiClient, onArtifactSelect, pageSize, projectId, selectedArtifact }: ProjectIterationsProps) {
  const iterations = useTreePages(
    ['artifact-tree', 'projects', projectId, 'iterations', pageSize],
    (cursor, signal) => apiClient.listProjectIterations({ cursor, limit: pageSize, projectId }, { signal }),
  )
  const iterationItems = useUniqueItems(iterations.data?.pages, (iteration) => iteration.iterationId)

  return (
    <div className="artifact-tree__branch" onClick={stopTreeItemActivation} role="group">
      <TreePage
        emptyDescription="이 프로젝트에는 표시할 이터레이션이 없습니다."
        errorDescription="이 프로젝트의 이터레이션을 다시 불러오세요."
        errorTitle="이터레이션을 불러올 수 없습니다"
        hasNextPage={iterations.hasNextPage}
        isError={iterations.isError}
        isFetchingNextPage={iterations.isFetchingNextPage}
        isPending={iterations.isPending}
        items={iterationItems}
        loadingLabel="이터레이션 목록을 불러오는 중"
        onLoadMore={() => {
          void iterations.fetchNextPage()
        }}
        onRetry={() => {
          void iterations.refetch()
        }}
        renderItem={(iteration) => (
          <IterationNode
            apiClient={apiClient}
            iteration={iteration}
            key={iteration.iterationId}
            onArtifactSelect={onArtifactSelect}
            pageSize={pageSize}
            selectedArtifact={selectedArtifact}
          />
        )}
      />
    </div>
  )
}

interface IterationNodeProps {
  readonly apiClient: DashboardApiClient
  readonly iteration: IterationSummary
  readonly onArtifactSelect: (selection: ArtifactTreeSelection) => void
  readonly pageSize: number
  readonly selectedArtifact: ArtifactTreeSelection | null
}

function IterationNode({ apiClient, iteration, onArtifactSelect, pageSize, selectedArtifact }: IterationNodeProps) {
  const [expanded, setExpanded] = useState(false)

  return (
    <TreeNode
      expanded={expanded}
      label={`이터레이션 ${iteration.label}`}
      level={2}
      onActivate={() => {
        setExpanded((current) => !current)
      }}
    >
      <span className="artifact-tree__type" aria-hidden="true">
        이터레이션
      </span>
      <span className="artifact-tree__label">{iteration.label}</span>
      {expanded ? (
        <IterationArtifacts
          apiClient={apiClient}
          iteration={iteration}
          onArtifactSelect={onArtifactSelect}
          pageSize={pageSize}
          selectedArtifact={selectedArtifact}
        />
      ) : null}
    </TreeNode>
  )
}

interface IterationArtifactsProps {
  readonly apiClient: DashboardApiClient
  readonly iteration: IterationSummary
  readonly onArtifactSelect: (selection: ArtifactTreeSelection) => void
  readonly pageSize: number
  readonly selectedArtifact: ArtifactTreeSelection | null
}

function IterationArtifacts({ apiClient, iteration, onArtifactSelect, pageSize, selectedArtifact }: IterationArtifactsProps) {
  const artifacts = useTreePages(
    ['artifact-tree', 'projects', iteration.projectId, 'iterations', iteration.iterationId, 'artifacts', pageSize],
    (cursor, signal) => apiClient.listArtifacts({
      artifactTypes: TREE_ARTIFACT_TYPES,
      cursor,
      iterationId: iteration.iterationId,
      limit: pageSize,
      projectId: iteration.projectId,
    }, { signal }),
  )
  const artifactItems = useUniqueItems(artifacts.data?.pages, (artifact) => `${artifact.artifactType}:${artifact.artifactId}`)
  const visibleArtifacts = artifactItems.filter(isTreeArtifact)

  return (
    <div className="artifact-tree__branch" onClick={stopTreeItemActivation} role="group">
      <TreePage
        emptyDescription="이 이터레이션에는 표시할 산출물이 없습니다."
        errorDescription="이 이터레이션의 산출물을 다시 불러오세요."
        errorTitle="산출물을 불러올 수 없습니다"
        hasNextPage={artifacts.hasNextPage}
        isError={artifacts.isError}
        isFetchingNextPage={artifacts.isFetchingNextPage}
        isPending={artifacts.isPending}
        items={visibleArtifacts}
        loadingLabel="산출물 목록을 불러오는 중"
        onLoadMore={() => {
          void artifacts.fetchNextPage()
        }}
        onRetry={() => {
          void artifacts.refetch()
        }}
        renderItem={(artifact) => {
          const nextSelection: ArtifactTreeSelection = {
            artifactId: artifact.artifactId,
            artifactType: artifact.artifactType,
            iterationId: artifact.iterationId,
            projectId: artifact.projectId,
          }
          const isSelected = selectionsMatch(selectedArtifact, nextSelection)

          return (
            <TreeNode
              key={`${artifact.artifactType}:${artifact.artifactId}`}
              label={`${artifactTypeLabel(artifact.artifactType)} ${artifact.title}`}
              level={3}
              onActivate={() => {
                onArtifactSelect(nextSelection)
              }}
              selectable
              selected={isSelected}
            >
              <span className="artifact-tree__type" aria-hidden="true">
                {artifactTypeLabel(artifact.artifactType)}
              </span>
              <span className="artifact-tree__label">{artifact.title}</span>
            </TreeNode>
          )
        }}
      />
    </div>
  )
}

function TreeNode({ children, expanded, label, level, onActivate, selectable = false, selected = false }: TreeNodeProps) {
  return (
    <div
      aria-current={selectable && selected ? 'true' : undefined}
      aria-expanded={expanded}
      aria-label={label}
      aria-level={level}
      aria-selected={selectable ? selected : undefined}
      className="artifact-tree__item"
      onClick={(event) => {
        const clickedItem = event.target instanceof HTMLElement
          ? event.target.closest<HTMLElement>('[role="treeitem"]')
          : null
        if (clickedItem === event.currentTarget) {
          onActivate()
        }
      }}
      role="treeitem"
      tabIndex={0}
    >
      <div className="artifact-tree__item-content">
        {expanded === undefined ? null : (
          <span aria-hidden="true" className="artifact-tree__disclosure">
            {expanded ? '▾' : '▸'}
          </span>
        )}
        {children}
      </div>
    </div>
  )
}

function TreePage<T>({
  emptyDescription,
  errorDescription,
  errorTitle,
  hasNextPage,
  isError,
  isFetchingNextPage,
  isPending,
  items,
  loadingLabel,
  onLoadMore,
  onRetry,
  renderItem,
}: TreePageProps<T>) {
  if (isPending) {
    return <TreeStatus label={loadingLabel} />
  }

  if (isError) {
    return (
      <div className="artifact-tree__message" role="alert">
        <span>{errorTitle}</span>
        <span>{errorDescription}</span>
        <button onClick={onRetry} type="button">
          다시 시도
        </button>
      </div>
    )
  }

  return (
    <>
      {items.length === 0 ? <TreeStatus label={emptyDescription} /> : items.map(renderItem)}
      {hasNextPage ? (
        <div className="artifact-tree__load-more">
          <button disabled={isFetchingNextPage} onClick={onLoadMore} type="button">
            {isFetchingNextPage ? '불러오는 중' : '더 불러오기'}
          </button>
        </div>
      ) : null}
    </>
  )
}

function TreeStatus({ label }: { readonly label: string }) {
  return (
    <div aria-label={label} className="artifact-tree__message" role="status">
      {label}
    </div>
  )
}

function stopTreeItemActivation(event: MouseEvent<HTMLDivElement>) {
  event.stopPropagation()
}

function useTreePages<T>(
  queryKey: readonly unknown[],
  loadPage: (cursor: string | undefined, signal: AbortSignal) => Promise<Page<T>>,
) {
  return useInfiniteQuery<Page<T>, Error, InfiniteData<Page<T>, string | undefined>, readonly unknown[], string | undefined>({
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => loadPage(pageParam, signal),
    queryKey,
    retry: false,
  })
}

function useUniqueItems<T>(pages: readonly Page<T>[] | undefined, keyFor: (item: T) => string): readonly T[] {
  return useMemo(() => {
    if (pages === undefined) {
      return []
    }

    const seen = new Set<string>()
    const items: T[] = []
    for (const page of pages) {
      for (const item of page.items) {
        const key = keyFor(item)
        if (!seen.has(key)) {
          seen.add(key)
          items.push(item)
        }
      }
    }
    return items
  }, [keyFor, pages])
}

function handleTreeKeyDown(event: KeyboardEvent<HTMLDivElement>) {
  const target = event.target
  if (!(target instanceof HTMLElement) || target.getAttribute('role') !== 'treeitem') {
    return
  }

  const tree = event.currentTarget
  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      moveTreeFocus(tree, target, 1)
      return
    case 'ArrowUp':
      event.preventDefault()
      moveTreeFocus(tree, target, -1)
      return
    case 'Home':
      event.preventDefault()
      focusTreeItem(tree, 0)
      return
    case 'End':
      event.preventDefault()
      focusTreeItem(tree, -1)
      return
    case 'ArrowRight':
      event.preventDefault()
      expandOrFocusChild(target)
      return
    case 'ArrowLeft':
      event.preventDefault()
      collapseOrFocusParent(target)
      return
    case 'Enter':
    case ' ':
      event.preventDefault()
      target.click()
      return
    default:
      return
  }
}

function moveTreeFocus(tree: HTMLElement, current: HTMLElement, offset: number) {
  const items = getTreeItems(tree)
  const index = items.indexOf(current)
  const next = items[index + offset]
  next?.focus()
}

function focusTreeItem(tree: HTMLElement, index: number) {
  const items = getTreeItems(tree)
  const item = index < 0 ? items.at(index) : items[index]
  item?.focus()
}

function expandOrFocusChild(item: HTMLElement) {
  const expanded = item.getAttribute('aria-expanded')
  if (expanded === 'false') {
    item.click()
    return
  }

  if (expanded === 'true') {
    const child = item.querySelector<HTMLElement>('[role="treeitem"]')
    child?.focus()
  }
}

function collapseOrFocusParent(item: HTMLElement) {
  if (item.getAttribute('aria-expanded') === 'true') {
    item.click()
    return
  }

  item.parentElement?.closest<HTMLElement>('[role="treeitem"]')?.focus()
}

function getTreeItems(tree: HTMLElement): readonly HTMLElement[] {
  return Array.from(tree.querySelectorAll<HTMLElement>('[role="treeitem"]'))
}

function normalizePageSize(value: number): number {
  return Number.isSafeInteger(value) && value > 0 ? value : DEFAULT_PAGE_SIZE
}

function isTreeArtifact(artifact: ArtifactLookupItem): artifact is ArtifactLookupItem & { readonly artifactType: DashboardArtifactType } {
  return TREE_ARTIFACT_TYPES.some((artifactType) => artifactType === artifact.artifactType)
}

function selectionsMatch(current: ArtifactTreeSelection | null, next: ArtifactTreeSelection): boolean {
  return current?.artifactId === next.artifactId && current.artifactType === next.artifactType
}

function artifactTypeLabel(artifactType: DashboardArtifactType): string {
  switch (artifactType) {
    case 'DOCUMENT_SNAPSHOT':
      return '문서 스냅샷'
    case 'TASK_GRAPH':
      return '작업 그래프'
    case 'TASK':
      return '작업'
    case 'RUN_RECORD':
      return '실행 기록'
    case 'PROPOSAL':
      return '제안'
  }
}
