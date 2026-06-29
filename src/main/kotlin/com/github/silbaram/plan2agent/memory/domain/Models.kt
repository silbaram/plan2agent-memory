package com.github.silbaram.plan2agent.memory.domain

import java.time.Instant

data class Project(
    val id: ProjectId,
    val sourceProjectId: SourceProjectId,
    val name: String,
    val canonicalServerId: CanonicalServerId,
    val rootPath: String,
    val sourceReference: SourceReference? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "Project name must not be blank" }
        require(rootPath.isNotBlank()) { "Project rootPath must not be blank" }
    }
}

data class Iteration(
    val id: IterationId,
    val projectId: ProjectId,
    val sourceIterationId: SourceIterationId,
    val label: String,
    val status: IterationStatus,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val sourceReference: SourceReference? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(label.isNotBlank()) { "Iteration label must not be blank" }
    }
}

enum class IterationStatus {
    PLANNED,
    ACTIVE,
    APPROVED,
    COMPLETED,
    ARCHIVED,
}

data class DocumentSnapshot(
    val id: DocumentId,
    val projectId: ProjectId,
    val iterationId: IterationId?,
    val sourceDocumentId: SourceDocumentId,
    val sourcePath: String,
    val snapshotVersion: Int,
    val artifactType: ArtifactType,
    val title: String,
    val content: String,
    val contentHash: ContentHash,
    val sourceReference: SourceReference? = null,
    val capturedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(title.isNotBlank()) { "DocumentSnapshot title must not be blank" }
        require(sourcePath.isNotBlank()) { "DocumentSnapshot sourcePath must not be blank" }
        require(snapshotVersion > 0) { "DocumentSnapshot snapshotVersion must be positive" }
    }
}

data class TaskGraph(
    val id: TaskGraphId,
    val projectId: ProjectId,
    val iterationId: IterationId,
    val sourceTaskGraphId: SourceTaskGraphId,
    val sourceDocumentId: SourceDocumentId?,
    val graphHash: ContentHash,
    val graphJson: String,
    val taskIds: Set<TaskId> = emptySet(),
    val dependencyEdges: Set<TaskDependency> = emptySet(),
    val sourceReference: SourceReference? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(graphJson.isNotBlank()) { "TaskGraph graphJson must not be blank" }
    }
}

data class TaskDependency(
    val fromTaskId: TaskId,
    val toTaskId: TaskId,
)

data class Task(
    val id: TaskId,
    val projectId: ProjectId,
    val iterationId: IterationId,
    val taskGraphId: TaskGraphId,
    val sourceTaskId: SourceTaskId,
    val title: String,
    val description: String,
    val status: TaskStatus,
    val targetArea: String,
    val dependencies: Set<TaskId> = emptySet(),
    val acceptanceCriteria: List<String> = emptyList(),
    val sourceReference: SourceReference? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(title.isNotBlank()) { "Task title must not be blank" }
        require(targetArea.isNotBlank()) { "Task targetArea must not be blank" }
    }
}

enum class TaskStatus {
    READY,
    BLOCKED,
    IN_PROGRESS,
    DONE,
}

data class RunRecord(
    val id: RunId,
    val projectId: ProjectId,
    val iterationId: IterationId,
    val taskId: TaskId,
    val sourceRunId: SourceRunId,
    val status: RunStatus,
    val agentTool: String,
    val runJson: String,
    val artifactRefs: List<ArtifactRef> = emptyList(),
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val sourceReference: SourceReference? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(agentTool.isNotBlank()) { "RunRecord agentTool must not be blank" }
        require(runJson.isNotBlank()) { "RunRecord runJson must not be blank" }
        require(finishedAt == null || !finishedAt.isBefore(startedAt)) {
            "RunRecord finishedAt must not be before startedAt"
        }
    }
}

enum class RunStatus {
    STARTED,
    FINISHED,
    FAILED,
    BLOCKED,
}

data class ArtifactRef(
    val artifactType: ArtifactType,
    val artifactId: String,
    val sourcePath: String? = null,
) {
    init {
        require(artifactId.isNotBlank()) { "ArtifactRef artifactId must not be blank" }
    }
}

data class DocumentChunk(
    val id: DocumentChunkId,
    val projectId: ProjectId,
    val iterationId: IterationId?,
    val documentId: DocumentId,
    val taskId: TaskId? = null,
    val runId: RunId? = null,
    val artifactType: ArtifactType,
    val sourcePath: String,
    val chunkIndex: Int,
    val content: String,
    val chunkHash: ContentHash,
    val tokenEstimate: Int? = null,
    val sourceReference: SourceReference? = null,
    val createdAt: Instant,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(sourcePath.isNotBlank()) { "DocumentChunk sourcePath must not be blank" }
        require(chunkIndex >= 0) { "DocumentChunk chunkIndex must not be negative" }
        require(content.isNotBlank()) { "DocumentChunk content must not be blank" }
        require(tokenEstimate == null || tokenEstimate >= 0) { "DocumentChunk tokenEstimate must not be negative" }
    }
}

data class EmbeddingSet(
    val id: EmbeddingSetId,
    val projectId: ProjectId,
    val embeddingModel: String,
    val embeddingDimension: Int,
    val embeddingVersion: String,
    val distanceMetric: DistanceMetric,
    val storageType: EmbeddingStorageType,
    val createdAt: Instant,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(embeddingModel.isNotBlank()) { "EmbeddingSet embeddingModel must not be blank" }
        require(embeddingDimension > 0) { "EmbeddingSet embeddingDimension must be positive" }
        require(embeddingVersion.isNotBlank()) { "EmbeddingSet embeddingVersion must not be blank" }
    }
}

data class ChunkEmbedding(
    val id: ChunkEmbeddingId,
    val embeddingSetId: EmbeddingSetId,
    val chunkId: DocumentChunkId,
    val embedding: Embedding,
    val embeddingHash: ContentHash? = null,
    val createdAt: Instant,
    val metadata: Map<String, String> = emptyMap(),
)

enum class DistanceMetric {
    COSINE,
    INNER_PRODUCT,
    L2,
}

enum class EmbeddingStorageType {
    INLINE,
    VECTOR_INDEX,
    EXTERNAL,
}

data class ArtifactSummary(
    val artifactType: ArtifactType,
    val artifactId: String,
    val projectId: ProjectId,
    val iterationId: IterationId? = null,
    val taskId: TaskId? = null,
    val runId: RunId? = null,
    val sourcePath: String? = null,
    val title: String,
    val contentHash: ContentHash? = null,
    val sourceReference: SourceReference? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(artifactId.isNotBlank()) { "ArtifactSummary artifactId must not be blank" }
        require(title.isNotBlank()) { "ArtifactSummary title must not be blank" }
    }
}

data class KeywordSearchMatch(
    val chunkId: DocumentChunkId?,
    val documentId: DocumentId?,
    val projectId: ProjectId,
    val iterationId: IterationId?,
    val artifactType: ArtifactType,
    val sourcePath: String?,
    val chunkIndex: Int?,
    val content: String,
    val score: Double,
    val matchReason: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(content.isNotBlank()) { "KeywordSearchMatch content must not be blank" }
        require(score >= 0.0) { "KeywordSearchMatch score must not be negative" }
        require(matchReason.isNotBlank()) { "KeywordSearchMatch matchReason must not be blank" }
        require(chunkIndex == null || chunkIndex >= 0) { "KeywordSearchMatch chunkIndex must not be negative" }
    }
}

data class VectorSearchMatch(
    val chunkId: DocumentChunkId?,
    val documentId: DocumentId?,
    val projectId: ProjectId,
    val iterationId: IterationId?,
    val artifactType: ArtifactType,
    val sourcePath: String?,
    val chunkIndex: Int?,
    val content: String,
    val score: Double,
    val distanceMetric: DistanceMetric,
    val embeddingModel: String,
    val embeddingVersion: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(content.isNotBlank()) { "VectorSearchMatch content must not be blank" }
        require(score >= 0.0) { "VectorSearchMatch score must not be negative" }
        require(embeddingModel.isNotBlank()) { "VectorSearchMatch embeddingModel must not be blank" }
        require(embeddingVersion.isNotBlank()) { "VectorSearchMatch embeddingVersion must not be blank" }
        require(chunkIndex == null || chunkIndex >= 0) { "VectorSearchMatch chunkIndex must not be negative" }
    }
}
