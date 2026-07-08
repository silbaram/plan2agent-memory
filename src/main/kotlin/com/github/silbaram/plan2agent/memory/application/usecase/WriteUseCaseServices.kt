package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.`in`.RegisterIterationUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.RegisterProjectUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.SaveDocumentChunksUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.SaveDocumentSnapshotUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.SaveRunRecordUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.SaveTaskGraphUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.SaveTasksUseCase
import com.github.silbaram.plan2agent.memory.application.port.out.ChunkEmbeddingStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentChunkStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentSnapshotStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingSetStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.IterationStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ProjectStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.RunRecordStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskGraphStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskStorePort
import com.github.silbaram.plan2agent.memory.domain.ChunkEmbedding
import com.github.silbaram.plan2agent.memory.domain.ChunkEmbeddingId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSet
import com.github.silbaram.plan2agent.memory.domain.Iteration
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.Project
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunRecord
import com.github.silbaram.plan2agent.memory.domain.Task
import com.github.silbaram.plan2agent.memory.domain.TaskGraph
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.util.UUID

@Service
class WriteUseCaseService(
    private val projectStore: ProjectStorePort,
    private val iterationStore: IterationStorePort,
    private val documentSnapshotStore: DocumentSnapshotStorePort,
    private val taskGraphStore: TaskGraphStorePort,
    private val taskStore: TaskStorePort,
    private val runRecordStore: RunRecordStorePort,
    private val documentChunkStore: DocumentChunkStorePort,
    private val embeddingSetStore: EmbeddingSetStorePort,
    private val chunkEmbeddingStore: ChunkEmbeddingStorePort,
) : RegisterProjectUseCase,
    RegisterIterationUseCase,
    SaveDocumentSnapshotUseCase,
    SaveTaskGraphUseCase,
    SaveTasksUseCase,
    SaveRunRecordUseCase,
    SaveDocumentChunksUseCase {

    @Transactional
    override fun registerProject(command: RegisterProjectCommand): Project =
        command.sourceReference.requireCanonicalServerId(command.canonicalServerId.value, "project").let {
        projectStore.save(
            Project(
                id = command.id,
                sourceProjectId = command.sourceProjectId,
                name = command.name,
                canonicalServerId = command.canonicalServerId,
                rootPath = command.rootPath,
                sourceReference = command.sourceReference,
                createdAt = command.createdAt,
                updatedAt = command.updatedAt,
                metadata = command.metadata,
            ),
        )
        }

    @Transactional
    override fun registerIteration(command: RegisterIterationCommand): Iteration {
        requireProjectExists(command.projectId)
        command.sourceReference.requireCanonicalServerId(command.id.value, "iteration")
        return iterationStore.save(
            Iteration(
                id = command.id,
                projectId = command.projectId,
                sourceIterationId = command.sourceIterationId,
                label = command.label,
                status = command.status,
                createdAt = command.createdAt,
                updatedAt = command.updatedAt,
                sourceReference = command.sourceReference,
                metadata = command.metadata,
            ),
        )
    }

    @Transactional
    override fun saveDocumentSnapshot(command: SaveDocumentSnapshotCommand): DocumentSnapshot {
        requireProjectExists(command.projectId)
        command.iterationId?.let { requireIterationBelongsToProject(it, command.projectId) }

        val requested = DocumentSnapshot(
            id = command.id,
            projectId = command.projectId,
            iterationId = command.iterationId,
            sourceDocumentId = command.sourceDocumentId,
            sourcePath = command.sourcePath.normalizedSourcePath(),
            snapshotVersion = command.snapshotVersion,
            artifactType = command.artifactType,
            title = command.title,
            content = command.content,
            contentHash = command.contentHash,
            sourceReference = command.sourceReference,
            capturedAt = command.capturedAt,
            createdAt = command.createdAt,
            updatedAt = command.updatedAt,
            metadata = command.metadata,
        )

        command.sourceReference.requireCanonicalServerId(command.id.value, "document")
        findExistingDocumentSnapshot(requested)?.let { return it }
        val prepared = applyDocumentSnapshotVersion(requested)
        return documentSnapshotStore.save(prepared)
    }

    @Transactional
    override fun saveTaskGraph(command: SaveTaskGraphCommand): TaskGraph {
        requireProjectExists(command.projectId)
        requireIterationBelongsToProject(command.iterationId, command.projectId)
        command.sourceDocumentId?.let {
            requireDocumentSourceExists(command.iterationId, it.value)
        }
        command.sourceReference.requireCanonicalServerId(command.id.value, "task graph")

        val requested = TaskGraph(
            id = command.id,
            projectId = command.projectId,
            iterationId = command.iterationId,
            sourceTaskGraphId = command.sourceTaskGraphId,
            sourceDocumentId = command.sourceDocumentId,
            graphHash = command.graphHash,
            graphJson = command.graphJson,
            taskIds = command.taskIds,
            dependencyEdges = command.dependencyEdges,
            sourceReference = command.sourceReference,
            createdAt = command.createdAt,
            updatedAt = command.updatedAt,
            metadata = command.metadata,
        )
        findExistingTaskGraph(requested)?.let { return it }
        return taskGraphStore.save(requested)
    }

    @Transactional
    override fun saveTasks(command: SaveTasksCommand): List<Task> {
        val taskGraph = requireTaskGraphExists(command.graphId)
        require(command.tasks.map { it.id }.toSet().size == command.tasks.size) {
            "Task batch contains duplicate task ids"
        }
        require(command.tasks.map { it.sourceTaskId }.toSet().size == command.tasks.size) {
            "Task batch contains duplicate sourceTaskIds"
        }
        require(command.tasks.all { it.taskGraphId == command.graphId }) {
            "All tasks must belong to task graph ${command.graphId.value}"
        }
        command.tasks.forEach {
            require(it.projectId == taskGraph.projectId) { "Task ${it.id.value} projectId must match task graph" }
            require(it.iterationId == taskGraph.iterationId) { "Task ${it.id.value} iterationId must match task graph" }
        }
        val taskIds = command.tasks.map { it.id }.toSet()
        val existingTaskIds = taskStore.findByGraphId(command.graphId).map { it.id }.toSet()
        command.tasks.forEach { task ->
            task.sourceReference.requireCanonicalServerId(task.id.value, "task")
            task.dependencies.forEach { dependency ->
                require(dependency in taskIds || dependency in existingTaskIds) {
                    "Task ${task.id.value} dependency ${dependency.value} is not in graph ${command.graphId.value}"
                }
            }
        }
        return taskStore.saveAll(command.tasks)
    }

    @Transactional
    override fun saveRunRecord(command: SaveRunRecordCommand): RunRecord {
        requireProjectExists(command.projectId)
        requireIterationBelongsToProject(command.iterationId, command.projectId)
        requireTaskBelongsToIteration(command.taskId, command.projectId, command.iterationId)
        require(command.runJson.isNotBlank()) { "runJson must not be blank" }
        command.sourceReference.requireCanonicalServerId(command.id.value, "run")

        return runRecordStore.save(
            RunRecord(
                id = command.id,
                projectId = command.projectId,
                iterationId = command.iterationId,
                taskId = command.taskId,
                sourceRunId = command.sourceRunId,
                status = command.status,
                agentTool = command.agentTool,
                runJson = command.runJson,
                artifactRefs = command.artifactRefs,
                startedAt = command.startedAt,
                finishedAt = command.finishedAt,
                sourceReference = command.sourceReference,
                createdAt = command.createdAt,
                updatedAt = command.updatedAt,
                metadata = command.metadata,
            ),
        )
    }

    @Transactional
    override fun saveDocumentChunks(command: SaveDocumentChunksCommand): List<DocumentChunk> {
        val document = requireDocumentExists(command.documentId)
        require(command.chunks.all { it.chunk.documentId == command.documentId }) {
            "All chunks must belong to document ${command.documentId.value}"
        }

        val writes = command.chunks.map { write ->
            write.copy(chunk = write.chunk.copy(sourcePath = write.chunk.sourcePath.normalizedSourcePath()))
        }
        val existingChunks = documentChunkStore.findByDocumentId(command.documentId)
        val existingByHash: Map<ContentHash, DocumentChunk> = existingChunks.associateBy { it.chunkHash }
        val existingById: Map<DocumentChunkId, DocumentChunk> = existingChunks.associateBy { it.id }
        val chunks = writes.map { write ->
            validateChunkRelations(write.chunk, document)
            write.chunk
        }
        require(chunks.map { it.id }.toSet().size == chunks.size) {
            "Document chunk batch contains duplicate chunk ids"
        }
        require(chunks.map { it.chunkHash }.toSet().size == chunks.size) {
            "Document chunk batch contains duplicate chunk hashes"
        }
        require(chunks.map { it.chunkIndex }.toSet().size == chunks.size) {
            "Document chunk batch contains duplicate chunk indexes"
        }
        chunks.forEach { chunk ->
            chunk.sourceReference.requireCanonicalServerId(chunk.id.value, "document chunk")
            existingById[chunk.id]?.let { existing ->
                require(existing.chunkHash == chunk.chunkHash && existing.documentId == chunk.documentId) {
                    "Chunk ${chunk.id.value} already maps to a different document or chunkHash"
                }
            }
        }
        val newChunks = chunks.filter { chunk -> !existingByHash.containsKey(chunk.chunkHash) }
        val newlySavedChunks = documentChunkStore.saveAll(newChunks)
        val savedChunks = chunks.map { chunk ->
            existingByHash[chunk.chunkHash] ?: newlySavedChunks.first { it.id == chunk.id }
        }
        val savedByInputId = savedChunks.associateBy { it.id }
        val savedByHash = savedChunks.associateBy { it.documentId to it.chunkHash }

        val chunkEmbeddings = writes.mapNotNull { write ->
            val embeddingSet = write.embeddingSet
            val embedding = write.embedding
            if (embeddingSet != null && embedding != null) {
                val savedChunk = savedByInputId[write.chunk.id] ?: savedByHash[write.chunk.documentId to write.chunk.chunkHash]
                requireNotNull(savedChunk) { "Saved chunk ${write.chunk.id.value} was not returned by the chunk store" }
                val resolvedSet = resolveEmbeddingSet(document.projectId, embeddingSet, embedding.values.size)
                ChunkEmbedding(
                    id = deterministicChunkEmbeddingId(savedChunk.id, resolvedSet.id),
                    embeddingSetId = resolvedSet.id,
                    chunkId = savedChunk.id,
                    embedding = embedding,
                    embeddingHash = write.embeddingHash,
                    createdAt = savedChunk.createdAt,
                    metadata = write.chunk.metadata,
                )
            } else {
                null
            }
        }
        if (chunkEmbeddings.isNotEmpty()) {
            chunkEmbeddingStore.saveAll(chunkEmbeddings)
        }

        return savedChunks
    }

    private fun findExistingDocumentSnapshot(document: DocumentSnapshot): DocumentSnapshot? {
        val existingSnapshots = document.iterationId
            ?.let { documentSnapshotStore.findByIterationId(it) }
            ?: emptyList()
        return existingSnapshots.firstOrNull {
            it.projectId == document.projectId &&
                it.artifactType == document.artifactType &&
                it.sourcePath == document.sourcePath &&
                it.contentHash == document.contentHash
        }
    }

    private fun applyDocumentSnapshotVersion(document: DocumentSnapshot): DocumentSnapshot {
        val existingSnapshots = document.iterationId
            ?.let { documentSnapshotStore.findByIterationId(it) }
            ?: emptyList()
        val logicalMatches = existingSnapshots.filter {
            it.projectId == document.projectId &&
                it.artifactType == document.artifactType &&
                it.sourcePath == document.sourcePath
        }
        val nextVersion = (logicalMatches.maxOfOrNull { it.snapshotVersion } ?: 0) + 1
        return document.copy(snapshotVersion = nextVersion.coerceAtLeast(document.snapshotVersion))
    }

    private fun findExistingTaskGraph(taskGraph: TaskGraph): TaskGraph? =
        taskGraphStore.findByIterationId(taskGraph.iterationId).firstOrNull {
            it.projectId == taskGraph.projectId &&
                (it.id == taskGraph.id ||
                    it.sourceTaskGraphId == taskGraph.sourceTaskGraphId ||
                    it.graphHash == taskGraph.graphHash)
        }

    private fun validateChunkRelations(chunk: DocumentChunk, document: DocumentSnapshot) {
        require(chunk.projectId == document.projectId) { "Chunk ${chunk.id.value} projectId must match document" }
        require(chunk.iterationId == document.iterationId) { "Chunk ${chunk.id.value} iterationId must match document" }
        require(chunk.artifactType == document.artifactType) { "Chunk ${chunk.id.value} artifactType must match document" }
        require(chunk.sourcePath == document.sourcePath) { "Chunk ${chunk.id.value} sourcePath must match document" }
        chunk.taskId?.let { requireTaskBelongsToIteration(it, chunk.projectId, requireNotNull(chunk.iterationId)) }
        chunk.runId?.let { requireRunBelongsToTask(it, chunk.taskId) }
    }

    private fun resolveEmbeddingSet(projectId: ProjectId, embeddingSet: EmbeddingSet, embeddingDimension: Int): EmbeddingSet {
        embeddingSet.metadata["p2a.sourceReference.canonicalServerId"]?.let {
            require(it == embeddingSet.id.value) {
                "EmbeddingSet source reference canonicalServerId must match embeddingSetId"
            }
        }
        require(embeddingSet.projectId == projectId) { "EmbeddingSet projectId must match document projectId" }
        require(embeddingSet.embeddingDimension == embeddingDimension) {
            "EmbeddingSet dimension ${embeddingSet.embeddingDimension} must match embedding dimension $embeddingDimension"
        }
        return embeddingSetStore.resolveOrCreate(embeddingSet)
    }

    private fun requireProjectExists(projectId: ProjectId): Project =
        requireNotNull(projectStore.findById(projectId)) { "Project ${projectId.value} was not found" }

    private fun requireIterationBelongsToProject(iterationId: IterationId, projectId: ProjectId): Iteration {
        val iteration = requireNotNull(iterationStore.findById(iterationId)) {
            "Iteration ${iterationId.value} was not found"
        }
        require(iteration.projectId == projectId) {
            "Iteration ${iterationId.value} does not belong to project ${projectId.value}"
        }
        return iteration
    }

    private fun requireDocumentExists(documentId: com.github.silbaram.plan2agent.memory.domain.DocumentId): DocumentSnapshot =
        requireNotNull(documentSnapshotStore.findById(documentId)) { "Document ${documentId.value} was not found" }

    private fun requireDocumentSourceExists(iterationId: IterationId, sourceDocumentId: String) {
        val exists = documentSnapshotStore.findByIterationId(iterationId).any {
            it.sourceDocumentId.value == sourceDocumentId
        }
        require(exists) { "Document source id $sourceDocumentId was not found in iteration ${iterationId.value}" }
    }

    private fun requireTaskGraphExists(taskGraphId: TaskGraphId): TaskGraph =
        requireNotNull(taskGraphStore.findById(taskGraphId)) { "Task graph ${taskGraphId.value} was not found" }

    private fun requireTaskBelongsToIteration(taskId: TaskId, projectId: ProjectId, iterationId: IterationId): Task {
        val task = requireNotNull(taskStore.findById(taskId)) { "Task ${taskId.value} was not found" }
        require(task.projectId == projectId && task.iterationId == iterationId) {
            "Task ${taskId.value} does not belong to project ${projectId.value} iteration ${iterationId.value}"
        }
        return task
    }

    private fun requireRunBelongsToTask(runId: com.github.silbaram.plan2agent.memory.domain.RunId, taskId: TaskId?) {
        val run = requireNotNull(runRecordStore.findById(runId)) { "Run ${runId.value} was not found" }
        if (taskId != null) {
            require(run.taskId == taskId) { "Run ${runId.value} does not belong to task ${taskId.value}" }
        }
    }
}

private fun deterministicChunkEmbeddingId(
    chunkId: com.github.silbaram.plan2agent.memory.domain.DocumentChunkId,
    embeddingSetId: com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId,
): ChunkEmbeddingId =
    ChunkEmbeddingId(
        UUID.nameUUIDFromBytes(
            "chunk-embedding:${chunkId.value}:${embeddingSetId.value}".toByteArray(StandardCharsets.UTF_8),
        ).toString(),
    )

private fun com.github.silbaram.plan2agent.memory.domain.SourceReference?.requireCanonicalServerId(
    canonicalId: String,
    label: String,
) {
    if (this == null) return
    require(canonicalServerId.value == canonicalId) {
        "$label sourceReference canonicalServerId must match canonical id $canonicalId"
    }
}

private fun String.normalizedSourcePath(): String =
    trim()
        .replace('\\', '/')
        .replace(Regex("/{2,}"), "/")
        .removePrefix("./")
