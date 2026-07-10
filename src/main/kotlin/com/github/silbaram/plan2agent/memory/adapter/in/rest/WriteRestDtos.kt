package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.usecase.DocumentChunkWrite
import com.github.silbaram.plan2agent.memory.application.usecase.RegisterIterationCommand
import com.github.silbaram.plan2agent.memory.application.usecase.RegisterProjectCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentChunksCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentSnapshotCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveRunRecordCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveArtifactGraphSnapshotCommand
import com.github.silbaram.plan2agent.memory.application.usecase.ArtifactGraphSnapshotResult
import com.github.silbaram.plan2agent.memory.application.usecase.SaveTaskGraphCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveTasksCommand
import com.github.silbaram.plan2agent.memory.domain.ArtifactRef
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSet
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingStorageType
import com.github.silbaram.plan2agent.memory.domain.Iteration
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.Project
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.RunRecord
import com.github.silbaram.plan2agent.memory.domain.RunStatus
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.SourceRunId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskId
import com.github.silbaram.plan2agent.memory.domain.Task
import com.github.silbaram.plan2agent.memory.domain.TaskDependency
import com.github.silbaram.plan2agent.memory.domain.TaskGraph
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.TaskStatus
import java.time.Instant

data class SourceReferenceDto(
    val canonicalServerId: String? = null,
    val uri: String? = null,
    val path: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val fragment: String? = null,
)

data class SourceReferenceResponse(
    val canonicalServerId: String,
    val uri: String,
    val path: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val fragment: String? = null,
)

data class ArtifactRefDto(
    val artifactType: String? = null,
    val artifactId: String? = null,
    val sourcePath: String? = null,
)

data class TaskDependencyDto(
    val fromTaskId: String? = null,
    val toTaskId: String? = null,
)

data class ArtifactLineageResponse(
    val projectId: String,
    val iterationId: String? = null,
    val sourcePath: String? = null,
    val contentHash: String? = null,
    val snapshotVersion: Int? = null,
    val taskId: String? = null,
    val runId: String? = null,
)

data class ProjectWriteRequest(
    val projectId: String? = null,
    val sourceProjectId: String? = null,
    val name: String? = null,
    val canonicalServerId: String? = null,
    val rootPath: String? = null,
    val sourceReference: SourceReferenceDto? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class ProjectResponse(
    val projectId: String,
    val canonicalServerId: String,
    val sourceProjectId: String,
    val name: String,
    val rootPath: String,
    val sourceReference: SourceReferenceResponse? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String>,
)

data class IterationWriteRequest(
    val iterationId: String? = null,
    val sourceIterationId: String? = null,
    val label: String? = null,
    val status: String? = null,
    val sourceReference: SourceReferenceDto? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class IterationResponse(
    val iterationId: String,
    val projectId: String,
    val sourceIterationId: String,
    val label: String,
    val status: String,
    val sourceReference: SourceReferenceResponse? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String>,
)

data class DocumentSnapshotWriteRequest(
    val documentId: String? = null,
    val projectId: String? = null,
    val iterationId: String? = null,
    val sourceDocumentId: String? = null,
    val sourcePath: String? = null,
    val snapshotVersion: Int? = null,
    val artifactType: String? = null,
    val title: String? = null,
    val content: String? = null,
    val contentHash: String? = null,
    val sourceReference: SourceReferenceDto? = null,
    val capturedAt: Instant? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class DocumentSnapshotResponse(
    val documentId: String,
    val projectId: String,
    val iterationId: String? = null,
    val sourceDocumentId: String,
    val sourcePath: String,
    val snapshotVersion: Int,
    val artifactType: String,
    val title: String,
    val contentHash: String,
    val lineage: ArtifactLineageResponse,
    val sourceReference: SourceReferenceResponse? = null,
    val capturedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String>,
)

data class TaskGraphWriteRequest(
    val taskGraphId: String? = null,
    val projectId: String? = null,
    val iterationId: String? = null,
    val sourceTaskGraphId: String? = null,
    val sourceDocumentId: String? = null,
    val graphHash: String? = null,
    val graphJson: String? = null,
    val taskIds: Set<String> = emptySet(),
    val dependencyEdges: Set<TaskDependencyDto> = emptySet(),
    val sourceReference: SourceReferenceDto? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class TaskGraphResponse(
    val taskGraphId: String,
    val projectId: String,
    val iterationId: String,
    val sourceTaskGraphId: String,
    val sourceDocumentId: String? = null,
    val graphHash: String,
    val taskIds: Set<String>,
    val dependencyEdges: Set<TaskDependencyDto>,
    val lineage: ArtifactLineageResponse,
    val sourceReference: SourceReferenceResponse? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String>,
)

data class TasksBulkWriteRequest(
    val graphId: String? = null,
    val tasks: List<TaskWriteRequest> = emptyList(),
)

data class TaskWriteRequest(
    val taskId: String? = null,
    val projectId: String? = null,
    val iterationId: String? = null,
    val taskGraphId: String? = null,
    val sourceTaskId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val targetArea: String? = null,
    val dependencies: Set<String> = emptySet(),
    val acceptanceCriteria: List<String> = emptyList(),
    val sourceReference: SourceReferenceDto? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class TaskResponse(
    val taskId: String,
    val projectId: String,
    val iterationId: String,
    val taskGraphId: String,
    val sourceTaskId: String,
    val title: String,
    val description: String,
    val status: String,
    val targetArea: String,
    val dependencies: Set<String>,
    val acceptanceCriteria: List<String>,
    val lineage: ArtifactLineageResponse,
    val sourceReference: SourceReferenceResponse? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String>,
)

data class RunRecordWriteRequest(
    val runId: String? = null,
    val projectId: String? = null,
    val iterationId: String? = null,
    val taskId: String? = null,
    val sourceRunId: String? = null,
    val status: String? = null,
    val agentTool: String? = null,
    val runJson: String? = null,
    val artifactRefs: List<ArtifactRefDto> = emptyList(),
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val sourceReference: SourceReferenceDto? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class RunRecordResponse(
    val runId: String,
    val projectId: String,
    val iterationId: String,
    val taskId: String,
    val sourceRunId: String,
    val status: String,
    val agentTool: String,
    val artifactRefs: List<ArtifactRefDto>,
    val lineage: ArtifactLineageResponse,
    val sourceReference: SourceReferenceResponse? = null,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String>,
)

data class DocumentChunksBulkWriteRequest(
    val documentId: String? = null,
    val chunks: List<DocumentChunkWriteRequest> = emptyList(),
)

data class DocumentChunkWriteRequest(
    val chunk: DocumentChunkRequest? = null,
    val embeddingSet: EmbeddingSetRequest? = null,
    val embedding: List<Float>? = null,
    val embeddingHash: String? = null,
)

data class DocumentChunkRequest(
    val chunkId: String? = null,
    val projectId: String? = null,
    val iterationId: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val artifactType: String? = null,
    val sourcePath: String? = null,
    val chunkIndex: Int? = null,
    val content: String? = null,
    val chunkHash: String? = null,
    val tokenEstimate: Int? = null,
    val sourceReference: SourceReferenceDto? = null,
    val createdAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class EmbeddingSetRequest(
    val embeddingSetId: String? = null,
    val projectId: String? = null,
    val embeddingModel: String? = null,
    val embeddingDimension: Int? = null,
    val embeddingVersion: String? = null,
    val distanceMetric: String? = null,
    val storageType: String? = null,
    val createdAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class DocumentChunkResponse(
    val chunkId: String,
    val projectId: String,
    val iterationId: String? = null,
    val documentId: String,
    val taskId: String? = null,
    val runId: String? = null,
    val artifactType: String,
    val sourcePath: String,
    val chunkIndex: Int,
    val chunkHash: String,
    val tokenEstimate: Int? = null,
    val lineage: ArtifactLineageResponse,
    val sourceReference: SourceReferenceResponse? = null,
    val createdAt: Instant,
    val metadata: Map<String, String>,
)

fun ProjectWriteRequest.toCommand(): RegisterProjectCommand {
    val projectId = ProjectId(requireText(projectId, "projectId"))
    return RegisterProjectCommand(
        id = projectId,
        sourceProjectId = SourceProjectId(requireText(sourceProjectId, "sourceProjectId")),
        name = requireText(name, "name"),
        canonicalServerId = CanonicalServerId(canonicalServerId?.trim()?.takeIf(String::isNotEmpty) ?: projectId.value),
        rootPath = requireText(rootPath, "rootPath"),
        sourceReference = sourceReference.toDomainSourceReference(),
        createdAt = createdAt ?: Instant.now(),
        updatedAt = updatedAt,
        metadata = metadata,
    )
}

fun IterationWriteRequest.toCommand(projectId: String): RegisterIterationCommand =
    RegisterIterationCommand(
        id = IterationId(requireText(iterationId, "iterationId")),
        projectId = ProjectId(requireText(projectId, "projectId")),
        sourceIterationId = SourceIterationId(requireText(sourceIterationId, "sourceIterationId")),
        label = requireText(label, "label"),
        status = parseRequiredEnum<IterationStatus>(status, "status"),
        sourceReference = sourceReference.toDomainSourceReference(),
        createdAt = createdAt ?: Instant.now(),
        updatedAt = updatedAt,
        metadata = metadata,
    )

fun DocumentSnapshotWriteRequest.toCommand(): SaveDocumentSnapshotCommand =
    SaveDocumentSnapshotCommand(
        id = DocumentId(requireText(documentId, "documentId")),
        projectId = ProjectId(requireText(projectId, "projectId")),
        iterationId = iterationId?.trim()?.takeIf(String::isNotEmpty)?.let(::IterationId),
        sourceDocumentId = SourceDocumentId(requireText(sourceDocumentId, "sourceDocumentId")),
        sourcePath = requireText(sourcePath, "sourcePath"),
        snapshotVersion = snapshotVersion ?: 1,
        artifactType = parseRequiredEnum(artifactType, "artifactType"),
        title = requireText(title, "title"),
        content = requireText(content, "content"),
        contentHash = ContentHash(requireText(contentHash, "contentHash")),
        sourceReference = sourceReference.toDomainSourceReference(),
        capturedAt = capturedAt ?: Instant.now(),
        createdAt = createdAt ?: Instant.now(),
        updatedAt = updatedAt,
        metadata = metadata,
    )

fun TaskGraphWriteRequest.toCommand(): SaveTaskGraphCommand =
    SaveTaskGraphCommand(
        id = TaskGraphId(requireText(taskGraphId, "taskGraphId")),
        projectId = ProjectId(requireText(projectId, "projectId")),
        iterationId = IterationId(requireText(iterationId, "iterationId")),
        sourceTaskGraphId = SourceTaskGraphId(requireText(sourceTaskGraphId, "sourceTaskGraphId")),
        sourceDocumentId = sourceDocumentId?.trim()?.takeIf(String::isNotEmpty)?.let(::SourceDocumentId),
        graphHash = ContentHash(requireText(graphHash, "graphHash")),
        graphJson = requireText(graphJson, "graphJson"),
        taskIds = taskIds.map { TaskId(requireText(it, "taskIds")) }.toSet(),
        dependencyEdges = dependencyEdges.map { it.toDomain() }.toSet(),
        sourceReference = sourceReference.toDomainSourceReference(),
        createdAt = createdAt ?: Instant.now(),
        updatedAt = updatedAt,
        metadata = metadata,
    )

fun TasksBulkWriteRequest.toCommand(): SaveTasksCommand {
    require(tasks.isNotEmpty()) { "tasks must not be empty" }
    return SaveTasksCommand(
        graphId = TaskGraphId(requireText(graphId, "graphId")),
        tasks = tasks.map { it.toDomain() },
    )
}

fun RunRecordWriteRequest.toCommand(): SaveRunRecordCommand =
    SaveRunRecordCommand(
        id = RunId(requireText(runId, "runId")),
        projectId = ProjectId(requireText(projectId, "projectId")),
        iterationId = IterationId(requireText(iterationId, "iterationId")),
        taskId = TaskId(requireText(taskId, "taskId")),
        sourceRunId = SourceRunId(requireText(sourceRunId, "sourceRunId")),
        status = parseRequiredEnum(status, "status"),
        agentTool = requireText(agentTool, "agentTool"),
        runJson = requireText(runJson, "runJson"),
        artifactRefs = artifactRefs.map { it.toDomain() },
        startedAt = requireNotNull(startedAt) { "startedAt is required" },
        finishedAt = finishedAt,
        sourceReference = sourceReference.toDomainSourceReference(),
        createdAt = createdAt ?: Instant.now(),
        updatedAt = updatedAt,
        metadata = metadata,
    )

fun DocumentChunksBulkWriteRequest.toCommand(): SaveDocumentChunksCommand {
    require(chunks.isNotEmpty()) { "chunks must not be empty" }
    val documentId = DocumentId(requireText(documentId, "documentId"))
    return SaveDocumentChunksCommand(
        documentId = documentId,
        chunks = chunks.map { it.toDomain(documentId) },
    )
}

fun Project.toResponse(): ProjectResponse =
    ProjectResponse(
        projectId = id.value,
        canonicalServerId = canonicalServerId.value,
        sourceProjectId = sourceProjectId.value,
        name = name,
        rootPath = rootPath,
        sourceReference = sourceReference?.toResponse(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadata = metadata,
    )

fun Iteration.toResponse(): IterationResponse =
    IterationResponse(
        iterationId = id.value,
        projectId = projectId.value,
        sourceIterationId = sourceIterationId.value,
        label = label,
        status = status.name,
        sourceReference = sourceReference?.toResponse(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadata = metadata,
    )

fun DocumentSnapshot.toResponse(): DocumentSnapshotResponse =
    DocumentSnapshotResponse(
        documentId = id.value,
        projectId = projectId.value,
        iterationId = iterationId?.value,
        sourceDocumentId = sourceDocumentId.value,
        sourcePath = sourcePath,
        snapshotVersion = snapshotVersion,
        artifactType = artifactType.name,
        title = title,
        contentHash = contentHash.value,
        lineage = ArtifactLineageResponse(
            projectId = projectId.value,
            iterationId = iterationId?.value,
            sourcePath = sourcePath,
            contentHash = contentHash.value,
            snapshotVersion = snapshotVersion,
        ),
        sourceReference = sourceReference?.toResponse(),
        capturedAt = capturedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadata = metadata + mapOf("sourceDocumentId" to sourceDocumentId.value),
    )

fun TaskGraph.toResponse(): TaskGraphResponse =
    TaskGraphResponse(
        taskGraphId = id.value,
        projectId = projectId.value,
        iterationId = iterationId.value,
        sourceTaskGraphId = sourceTaskGraphId.value,
        sourceDocumentId = sourceDocumentId?.value,
        graphHash = graphHash.value,
        taskIds = taskIds.map { it.value }.toSet(),
        dependencyEdges = dependencyEdges.map { it.toDto() }.toSet(),
        lineage = ArtifactLineageResponse(
            projectId = projectId.value,
            iterationId = iterationId.value,
            contentHash = graphHash.value,
        ),
        sourceReference = sourceReference?.toResponse(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadata = metadata + mapOf("sourceTaskGraphId" to sourceTaskGraphId.value),
    )

fun Task.toResponse(): TaskResponse =
    TaskResponse(
        taskId = id.value,
        projectId = projectId.value,
        iterationId = iterationId.value,
        taskGraphId = taskGraphId.value,
        sourceTaskId = sourceTaskId.value,
        title = title,
        description = description,
        status = status.name,
        targetArea = targetArea,
        dependencies = dependencies.map { it.value }.toSet(),
        acceptanceCriteria = acceptanceCriteria,
        lineage = ArtifactLineageResponse(
            projectId = projectId.value,
            iterationId = iterationId.value,
            taskId = id.value,
        ),
        sourceReference = sourceReference?.toResponse(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadata = metadata + mapOf("sourceTaskId" to sourceTaskId.value),
    )

fun RunRecord.toResponse(): RunRecordResponse =
    RunRecordResponse(
        runId = id.value,
        projectId = projectId.value,
        iterationId = iterationId.value,
        taskId = taskId.value,
        sourceRunId = sourceRunId.value,
        status = status.name,
        agentTool = agentTool,
        artifactRefs = artifactRefs.map { it.toDto() },
        lineage = ArtifactLineageResponse(
            projectId = projectId.value,
            iterationId = iterationId.value,
            taskId = taskId.value,
            runId = id.value,
        ),
        sourceReference = sourceReference?.toResponse(),
        startedAt = startedAt,
        finishedAt = finishedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadata = metadata + mapOf("sourceRunId" to sourceRunId.value),
    )

fun DocumentChunk.toResponse(): DocumentChunkResponse =
    DocumentChunkResponse(
        chunkId = id.value,
        projectId = projectId.value,
        iterationId = iterationId?.value,
        documentId = documentId.value,
        taskId = taskId?.value,
        runId = runId?.value,
        artifactType = artifactType.name,
        sourcePath = sourcePath,
        chunkIndex = chunkIndex,
        chunkHash = chunkHash.value,
        tokenEstimate = tokenEstimate,
        lineage = ArtifactLineageResponse(
            projectId = projectId.value,
            iterationId = iterationId?.value,
            sourcePath = sourcePath,
            contentHash = chunkHash.value,
            taskId = taskId?.value,
            runId = runId?.value,
        ),
        sourceReference = sourceReference?.toResponse(),
        createdAt = createdAt,
        metadata = metadata,
    )

private fun TaskWriteRequest.toDomain(): Task =
    Task(
        id = TaskId(requireText(taskId, "taskId")),
        projectId = ProjectId(requireText(projectId, "projectId")),
        iterationId = IterationId(requireText(iterationId, "iterationId")),
        taskGraphId = TaskGraphId(requireText(taskGraphId, "taskGraphId")),
        sourceTaskId = SourceTaskId(requireText(sourceTaskId, "sourceTaskId")),
        title = requireText(title, "title"),
        description = description ?: "",
        status = parseRequiredEnum(status, "status"),
        targetArea = requireText(targetArea, "targetArea"),
        dependencies = dependencies.map { TaskId(requireText(it, "dependencies")) }.toSet(),
        acceptanceCriteria = acceptanceCriteria,
        sourceReference = sourceReference.toDomainSourceReference(),
        createdAt = createdAt ?: Instant.now(),
        updatedAt = updatedAt,
        metadata = metadata,
    )

private fun DocumentChunkWriteRequest.toDomain(documentId: DocumentId): DocumentChunkWrite {
    val chunk = requireNotNull(chunk) { "chunk is required" }.toDomain(documentId)
    val hasEmbeddingSet = embeddingSet != null
    val hasEmbedding = embedding != null
    require(hasEmbeddingSet == hasEmbedding) { "embeddingSet and embedding must be supplied together" }
    val embeddingValues = embedding?.let { values ->
        require(values.isNotEmpty()) { "embedding must not be empty" }
        require(values.all { it.isFinite() }) { "embedding values must be finite" }
        Embedding(values)
    }
    return DocumentChunkWrite(
        chunk = chunk,
        embeddingSet = embeddingSet?.toDomain(defaultProjectId = chunk.projectId),
        embedding = embeddingValues,
        embeddingHash = embeddingHash?.trim()?.takeIf(String::isNotEmpty)?.let(::ContentHash),
    )
}

private fun DocumentChunkRequest.toDomain(documentId: DocumentId): DocumentChunk =
    DocumentChunk(
        id = DocumentChunkId(requireText(chunkId, "chunkId")),
        projectId = ProjectId(requireText(projectId, "projectId")),
        iterationId = iterationId?.trim()?.takeIf(String::isNotEmpty)?.let(::IterationId),
        documentId = documentId,
        taskId = taskId?.trim()?.takeIf(String::isNotEmpty)?.let(::TaskId),
        runId = runId?.trim()?.takeIf(String::isNotEmpty)?.let(::RunId),
        artifactType = parseRequiredEnum(artifactType, "artifactType"),
        sourcePath = requireText(sourcePath, "sourcePath"),
        chunkIndex = requireNotNull(chunkIndex) { "chunkIndex is required" },
        content = requireText(content, "content"),
        chunkHash = ContentHash(requireText(chunkHash, "chunkHash")),
        tokenEstimate = tokenEstimate,
        sourceReference = sourceReference.toDomainSourceReference(),
        createdAt = createdAt ?: Instant.now(),
        metadata = metadata,
    )

private fun EmbeddingSetRequest.toDomain(defaultProjectId: ProjectId): EmbeddingSet =
    EmbeddingSet(
        id = EmbeddingSetId(requireText(embeddingSetId, "embeddingSetId")),
        projectId = projectId?.trim()?.takeIf(String::isNotEmpty)?.let(::ProjectId) ?: defaultProjectId,
        embeddingModel = requireText(embeddingModel, "embeddingModel"),
        embeddingDimension = requireNotNull(embeddingDimension) { "embeddingDimension is required" },
        embeddingVersion = requireText(embeddingVersion, "embeddingVersion"),
        distanceMetric = parseRequiredEnum(distanceMetric ?: DistanceMetric.COSINE.name, "distanceMetric"),
        storageType = parseRequiredEnum(storageType ?: EmbeddingStorageType.VECTOR_INDEX.name, "storageType"),
        createdAt = createdAt ?: Instant.now(),
        metadata = metadata,
    )

private fun TaskDependencyDto.toDomain(): TaskDependency =
    TaskDependency(
        fromTaskId = TaskId(requireText(fromTaskId, "fromTaskId")),
        toTaskId = TaskId(requireText(toTaskId, "toTaskId")),
    )

private fun TaskDependency.toDto(): TaskDependencyDto =
    TaskDependencyDto(fromTaskId = fromTaskId.value, toTaskId = toTaskId.value)

private fun ArtifactRefDto.toDomain(): ArtifactRef =
    ArtifactRef(
        artifactType = parseRequiredEnum(artifactType, "artifactType"),
        artifactId = requireText(artifactId, "artifactId"),
        sourcePath = sourcePath,
    )

private fun ArtifactRef.toDto(): ArtifactRefDto =
    ArtifactRefDto(artifactType = artifactType.name, artifactId = artifactId, sourcePath = sourcePath)

private fun SourceReferenceDto?.toDomainSourceReference(): SourceReference? {
    if (this == null) return null
    return SourceReference(
        canonicalServerId = CanonicalServerId(requireText(canonicalServerId, "sourceReference.canonicalServerId")),
        uri = requireText(uri, "sourceReference.uri"),
        path = path,
        startLine = startLine,
        endLine = endLine,
        fragment = fragment,
    )
}

private fun SourceReference.toResponse(): SourceReferenceResponse =
    SourceReferenceResponse(
        canonicalServerId = canonicalServerId.value,
        uri = uri,
        path = path,
        startLine = startLine,
        endLine = endLine,
        fragment = fragment,
    )

internal fun requireText(value: String?, field: String): String {
    require(!value.isNullOrBlank()) { "$field is required" }
    return value.trim()
}

private inline fun <reified T : Enum<T>> parseRequiredEnum(value: String?, field: String): T =
    try {
        enumValueOf<T>(requireText(value, field).uppercase())
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("$field has invalid value")
    }

data class ArtifactGraphSnapshotWriteRequest(
    val projectId: String? = null,
    val iterationId: String? = null,
    val nodes: List<ArtifactNodeWriteRequest> = emptyList(),
    val edges: List<ArtifactEdgeWriteRequest> = emptyList(),
)

data class ArtifactNodeWriteRequest(
    val nodeId: String? = null,
    val nodeKind: String? = null,
    val naturalKey: String? = null,
    val label: String? = null,
    val content: String? = null,
    val documentId: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class ArtifactEdgeWriteRequest(
    val edgeId: String? = null,
    val fromNodeId: String? = null,
    val toNodeId: String? = null,
    val edgeType: String? = null,
    val sourceReference: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class ArtifactGraphSnapshotResponse(val nodeCount: Int, val edgeCount: Int)

fun ArtifactGraphSnapshotWriteRequest.toCommand(): SaveArtifactGraphSnapshotCommand {
    val pid = ProjectId(requireText(projectId, "projectId"))
    val iid = iterationId?.trim()?.takeIf(String::isNotEmpty)?.let(::IterationId)
    return SaveArtifactGraphSnapshotCommand(pid, iid, nodes.map { it.toDomain(pid, iid) }, edges.map { it.toDomain(pid) })
}

private fun ArtifactNodeWriteRequest.toDomain(projectId: ProjectId, iterationId: IterationId?) = com.github.silbaram.plan2agent.memory.domain.ArtifactNode(
    id = com.github.silbaram.plan2agent.memory.domain.ArtifactNodeId(requireText(nodeId, "nodeId")),
    projectId = projectId,
    iterationId = iterationId,
    kind = parseRequiredEnum(nodeKind, "nodeKind"),
    naturalKey = requireText(naturalKey, "naturalKey"),
    label = requireText(label, "label"),
    content = content,
    documentId = documentId?.trim()?.takeIf(String::isNotEmpty)?.let(::DocumentId),
    taskId = taskId?.trim()?.takeIf(String::isNotEmpty)?.let(::TaskId),
    runId = runId?.trim()?.takeIf(String::isNotEmpty)?.let(::RunId),
    metadata = metadata,
)

private fun ArtifactEdgeWriteRequest.toDomain(projectId: ProjectId) = com.github.silbaram.plan2agent.memory.domain.ArtifactEdge(
    id = com.github.silbaram.plan2agent.memory.domain.ArtifactEdgeId(requireText(edgeId, "edgeId")),
    projectId = projectId,
    fromNodeId = com.github.silbaram.plan2agent.memory.domain.ArtifactNodeId(requireText(fromNodeId, "fromNodeId")),
    toNodeId = com.github.silbaram.plan2agent.memory.domain.ArtifactNodeId(requireText(toNodeId, "toNodeId")),
    type = parseRequiredEnum(edgeType, "edgeType"),
    sourceReference = sourceReference?.trim()?.takeIf(String::isNotEmpty),
    metadata = metadata,
)

fun ArtifactGraphSnapshotResult.toResponse(): ArtifactGraphSnapshotResponse = ArtifactGraphSnapshotResponse(nodeCount, edgeCount)
