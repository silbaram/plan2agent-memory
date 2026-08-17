import { useInfiniteQuery, type InfiniteData } from '@tanstack/react-query'
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type ReactNode,
} from 'react'
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
  readonly sourceIterationId: string | null
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
  readonly initialRovingCandidate?: boolean
  readonly label: string
  readonly level: number
  readonly nodeId: string
  readonly onActivate: () => void
  readonly parentNodeId?: string
  readonly selectable?: boolean
  readonly selected?: boolean
}

interface TreePageAuxiliaryProps<T = unknown> {
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
}

interface TreePageProps<T> extends TreePageAuxiliaryProps<T> {
  readonly renderItem: (item: T) => ReactNode
}

interface TreeRovingFocusContextValue {
  readonly activeNodeId: string | null
  readonly onTreeNodeFocus: (nodeId: string) => void
  readonly scheduleRovingFocusCheck: () => void
}

interface TreeItemSnapshot {
  readonly index: number
  readonly nodeId: string
  readonly parentNodeId: string | null
}

const TreeRovingFocusContext = createContext<TreeRovingFocusContextValue | null>(null)

export function ArtifactTree({
  apiClient = dashboardApi,
  ariaLabel = '산출물 계층',
  onArtifactSelect,
  pageSize = DEFAULT_PAGE_SIZE,
  selectedArtifact,
}: ArtifactTreeProps) {
  const headingId = useId()
  const [uncontrolledSelection, setUncontrolledSelection] = useState<ArtifactTreeSelection | null>(null)
  const [activeNodeId, setActiveNodeId] = useState<string | null>(null)
  const activeNodeIdRef = useRef<string | null>(null)
  const lastFocusedNodeIdRef = useRef<string | null>(null)
  const pendingFocusNodeIdRef = useRef<string | null>(null)
  const previousTreeItemsRef = useRef<readonly TreeItemSnapshot[]>([])
  const rovingFocusCheckScheduledRef = useRef(false)
  const treeRef = useRef<HTMLDivElement>(null)
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

  const setRovingNodeId = useCallback((nodeId: string) => {
    activeNodeIdRef.current = nodeId
    setActiveNodeId(nodeId)
  }, [])

  const reconcileRovingFocus = useCallback(() => {
    const tree = treeRef.current
    if (tree === null) {
      return
    }

    const currentTreeItems = readTreeItemSnapshots(tree)
    const currentActiveNodeId = activeNodeIdRef.current
    if (currentTreeItems.length === 0) {
      if (currentActiveNodeId !== null && lastFocusedNodeIdRef.current === currentActiveNodeId) {
        pendingFocusNodeIdRef.current = currentActiveNodeId
      }
      return
    }

    const activeItem = currentActiveNodeId === null
      ? null
      : currentTreeItems.find((item) => item.nodeId === currentActiveNodeId) ?? null

    if (activeItem !== null) {
      previousTreeItemsRef.current = currentTreeItems
      if (pendingFocusNodeIdRef.current === activeItem.nodeId) {
        focusTreeItemById(tree, activeItem.nodeId)
        pendingFocusNodeIdRef.current = null
      }
      return
    }

    if (currentActiveNodeId === null) {
      previousTreeItemsRef.current = currentTreeItems
      setRovingNodeId(currentTreeItems[0].nodeId)
      return
    }

    const fallback = findClosestVisibleTreeItem(
      currentTreeItems,
      previousTreeItemsRef.current,
      currentActiveNodeId,
    )
    previousTreeItemsRef.current = currentTreeItems
    if (fallback === null) {
      return
    }

    const shouldRestoreFocus = lastFocusedNodeIdRef.current === currentActiveNodeId
      || pendingFocusNodeIdRef.current === currentActiveNodeId
    setRovingNodeId(fallback.nodeId)
    if (shouldRestoreFocus) {
      pendingFocusNodeIdRef.current = fallback.nodeId
    }
  }, [setRovingNodeId])

  const scheduleRovingFocusCheck = useCallback(() => {
    if (rovingFocusCheckScheduledRef.current) {
      return
    }

    rovingFocusCheckScheduledRef.current = true
    queueMicrotask(() => {
      rovingFocusCheckScheduledRef.current = false
      reconcileRovingFocus()
    })
  }, [reconcileRovingFocus])

  const onTreeNodeFocus = useCallback((nodeId: string) => {
    lastFocusedNodeIdRef.current = nodeId
    pendingFocusNodeIdRef.current = null
    setRovingNodeId(nodeId)
  }, [setRovingNodeId])

  useEffect(() => {
    scheduleRovingFocusCheck()
  }, [activeNodeId, scheduleRovingFocusCheck])

  return (
    <section aria-labelledby={headingId} className="artifact-tree">
      <h2 className="artifact-tree__heading" id={headingId}>
        {ariaLabel}
      </h2>
      <TreeRovingFocusContext.Provider value={{ activeNodeId, onTreeNodeFocus, scheduleRovingFocusCheck }}>
        <TreePageAuxiliary
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
        />
        <div
          aria-label={ariaLabel}
          className="artifact-tree__root"
          onBlur={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget)) {
              lastFocusedNodeIdRef.current = null
            }
          }}
          onKeyDown={handleTreeKeyDown}
          ref={treeRef}
          role="tree"
        >
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
                isInitialRovingCandidate={projectItems[0]?.projectId === project.projectId}
                key={project.projectId}
                onArtifactSelect={handleArtifactSelect}
                pageSize={effectivePageSize}
                project={project}
                selectedArtifact={selection}
              />
            )}
          />
        </div>
      </TreeRovingFocusContext.Provider>
    </section>
  )
}

interface ProjectNodeProps {
  readonly apiClient: DashboardApiClient
  readonly isInitialRovingCandidate: boolean
  readonly onArtifactSelect: (selection: ArtifactTreeSelection) => void
  readonly pageSize: number
  readonly project: ProjectSummary
  readonly selectedArtifact: ArtifactTreeSelection | null
}

function ProjectNode({
  apiClient,
  isInitialRovingCandidate,
  onArtifactSelect,
  pageSize,
  project,
  selectedArtifact,
}: ProjectNodeProps) {
  const [expanded, setExpanded] = useState(false)
  const nodeId = projectTreeNodeId(project.projectId)

  return (
    <TreeNode
      expanded={expanded}
      initialRovingCandidate={isInitialRovingCandidate}
      label={`프로젝트 ${project.name}`}
      level={1}
      nodeId={nodeId}
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
          projectNodeId={nodeId}
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
  readonly projectNodeId: string
  readonly selectedArtifact: ArtifactTreeSelection | null
}

function ProjectIterations({
  apiClient,
  onArtifactSelect,
  pageSize,
  projectId,
  projectNodeId,
  selectedArtifact,
}: ProjectIterationsProps) {
  const iterations = useTreePages(
    ['artifact-tree', 'projects', projectId, 'iterations', pageSize],
    (cursor, signal) => apiClient.listProjectIterations({ cursor, limit: pageSize, projectId }, { signal }),
  )
  const iterationItems = useUniqueItems(iterations.data?.pages, (iteration) => iteration.iterationId)

  return (
    <>
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
              projectNodeId={projectNodeId}
              selectedArtifact={selectedArtifact}
            />
          )}
        />
      </div>
      <div className="artifact-tree__auxiliary" onClick={stopTreeItemActivation}>
        <TreePageAuxiliary
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
        />
      </div>
    </>
  )
}

interface IterationNodeProps {
  readonly apiClient: DashboardApiClient
  readonly iteration: IterationSummary
  readonly onArtifactSelect: (selection: ArtifactTreeSelection) => void
  readonly pageSize: number
  readonly projectNodeId: string
  readonly selectedArtifact: ArtifactTreeSelection | null
}

function IterationNode({
  apiClient,
  iteration,
  onArtifactSelect,
  pageSize,
  projectNodeId,
  selectedArtifact,
}: IterationNodeProps) {
  const [expanded, setExpanded] = useState(false)
  const nodeId = iterationTreeNodeId(iteration.projectId, iteration.iterationId)

  return (
    <TreeNode
      expanded={expanded}
      label={`이터레이션 ${iteration.label}`}
      level={2}
      nodeId={nodeId}
      onActivate={() => {
        setExpanded((current) => !current)
      }}
      parentNodeId={projectNodeId}
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
          parentNodeId={nodeId}
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
  readonly parentNodeId: string
  readonly selectedArtifact: ArtifactTreeSelection | null
}

function IterationArtifacts({
  apiClient,
  iteration,
  onArtifactSelect,
  pageSize,
  parentNodeId,
  selectedArtifact,
}: IterationArtifactsProps) {
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
    <>
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
              sourceIterationId: iteration.sourceIterationId,
            }
            const isSelected = selectionsMatch(selectedArtifact, nextSelection)

            return (
              <TreeNode
                key={`${artifact.artifactType}:${artifact.artifactId}`}
                label={`${artifactTypeLabel(artifact.artifactType)} ${artifact.title}`}
                level={3}
                nodeId={artifactTreeNodeId(artifact)}
                onActivate={() => {
                  onArtifactSelect(nextSelection)
                }}
                selectable
                selected={isSelected}
                parentNodeId={parentNodeId}
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
      <div className="artifact-tree__auxiliary" onClick={stopTreeItemActivation}>
        <TreePageAuxiliary
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
        />
      </div>
    </>
  )
}

function TreeNode({
  children,
  expanded,
  initialRovingCandidate = false,
  label,
  level,
  nodeId,
  onActivate,
  parentNodeId,
  selectable = false,
  selected = false,
}: TreeNodeProps) {
  const { activeNodeId, onTreeNodeFocus, scheduleRovingFocusCheck } = useTreeRovingFocus()
  const isRovingTabstop = activeNodeId === nodeId || (activeNodeId === null && initialRovingCandidate)

  useEffect(() => {
    scheduleRovingFocusCheck()
    return () => {
      scheduleRovingFocusCheck()
    }
  }, [nodeId, scheduleRovingFocusCheck])

  return (
    <div
      aria-current={selectable && selected ? 'true' : undefined}
      aria-expanded={expanded}
      aria-label={label}
      aria-level={level}
      aria-selected={selectable ? selected : undefined}
      className="artifact-tree__item"
      data-roving-tabstop={isRovingTabstop ? 'true' : undefined}
      data-tree-node-id={nodeId}
      data-tree-parent-node-id={parentNodeId}
      onClick={(event) => {
        const clickedItem = event.target instanceof HTMLElement
          ? event.target.closest<HTMLElement>('[role="treeitem"]')
          : null
        if (clickedItem === event.currentTarget) {
          event.currentTarget.focus()
          onTreeNodeFocus(nodeId)
          onActivate()
        }
      }}
      onFocus={(event) => {
        if (event.target === event.currentTarget) {
          onTreeNodeFocus(nodeId)
        }
      }}
      role="treeitem"
      tabIndex={isRovingTabstop ? 0 : -1}
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
  hasNextPage,
  isError,
  isPending,
  items,
  renderItem,
}: TreePageProps<T>) {
  const { scheduleRovingFocusCheck } = useTreeRovingFocus()

  useEffect(() => {
    scheduleRovingFocusCheck()
  }, [hasNextPage, isError, isPending, items, scheduleRovingFocusCheck])

  if (isPending || isError) {
    return null
  }

  return items.map(renderItem)
}

function TreePageAuxiliary({
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
}: TreePageAuxiliaryProps) {
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
      {items.length === 0 ? <TreeStatus label={emptyDescription} /> : null}
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

function useTreeRovingFocus(): TreeRovingFocusContextValue {
  const context = useContext(TreeRovingFocusContext)
  if (context === null) {
    throw new Error('ArtifactTree nodes must be rendered inside the tree roving-focus provider.')
  }
  return context
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

function readTreeItemSnapshots(tree: HTMLElement): readonly TreeItemSnapshot[] {
  return getTreeItems(tree).flatMap((item, index) => {
    const nodeId = treeNodeIdFromElement(item)
    if (nodeId === null) {
      return []
    }

    return [{
      index,
      nodeId,
      parentNodeId: item.dataset.treeParentNodeId ?? null,
    }]
  })
}

function treeNodeIdFromElement(item: HTMLElement): string | null {
  const nodeId = item.dataset.treeNodeId
  return nodeId === undefined || nodeId.length === 0 ? null : nodeId
}

function findClosestVisibleTreeItem(
  currentItems: readonly TreeItemSnapshot[],
  previousItems: readonly TreeItemSnapshot[],
  missingNodeId: string,
): TreeItemSnapshot | null {
  const previousItem = previousItems.find((item) => item.nodeId === missingNodeId)
  if (previousItem === undefined) {
    return currentItems[0] ?? null
  }

  let ancestorNodeId = previousItem.parentNodeId
  while (ancestorNodeId !== null) {
    const visibleAncestor = currentItems.find((item) => item.nodeId === ancestorNodeId)
    if (visibleAncestor !== undefined) {
      return visibleAncestor
    }

    ancestorNodeId = previousItems.find((item) => item.nodeId === ancestorNodeId)?.parentNodeId ?? null
  }

  return currentItems[Math.min(previousItem.index, currentItems.length - 1)] ?? null
}

function focusTreeItemById(tree: HTMLElement, nodeId: string) {
  const item = getTreeItems(tree).find((candidate) => treeNodeIdFromElement(candidate) === nodeId)
  item?.focus()
}

function normalizePageSize(value: number): number {
  return Number.isSafeInteger(value) && value > 0 ? value : DEFAULT_PAGE_SIZE
}

function projectTreeNodeId(projectId: string): string {
  return `project:${encodeURIComponent(projectId)}`
}

function iterationTreeNodeId(projectId: string, iterationId: string): string {
  return `iteration:${encodeURIComponent(projectId)}:${encodeURIComponent(iterationId)}`
}

function artifactTreeNodeId(artifact: ArtifactLookupItem): string {
  return [
    'artifact',
    encodeURIComponent(artifact.projectId),
    encodeURIComponent(artifact.iterationId ?? ''),
    encodeURIComponent(artifact.artifactType),
    encodeURIComponent(artifact.artifactId),
  ].join(':')
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
