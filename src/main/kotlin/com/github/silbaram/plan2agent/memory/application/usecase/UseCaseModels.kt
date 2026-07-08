package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.ArtifactRef
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSet
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.RunStatus
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceRunId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskId
import com.github.silbaram.plan2agent.memory.domain.Task
import com.github.silbaram.plan2agent.memory.domain.TaskDependency
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import java.time.Instant

data class RegisterProjectCommand(
    val id: ProjectId,
    val sourceProjectId: SourceProjectId,
    val name: String,
    val canonicalServerId: CanonicalServerId,
    val rootPath: String,
    val sourceReference: SourceReference? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class RegisterIterationCommand(
    val id: IterationId,
    val projectId: ProjectId,
    val sourceIterationId: SourceIterationId,
    val label: String,
    val status: IterationStatus,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val sourceReference: SourceReference? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class SaveDocumentSnapshotCommand(
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
)

data class SaveTaskGraphCommand(
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
)

data class SaveTasksCommand(
    val graphId: TaskGraphId,
    val tasks: List<Task>,
)

data class SaveRunRecordCommand(
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
)

data class SaveDocumentChunksCommand(
    val documentId: DocumentId,
    val chunks: List<DocumentChunkWrite>,
)

data class DocumentChunkWrite(
    val chunk: DocumentChunk,
    val embeddingSet: EmbeddingSet? = null,
    val embedding: Embedding? = null,
    val embeddingHash: ContentHash? = null,
) {
    init {
        require((embeddingSet == null) == (embedding == null)) {
            "DocumentChunkWrite embeddingSet and embedding must be supplied together"
        }
    }
}

data class PagedResult<T>(
    val items: List<T>,
    val nextCursor: String? = null,
)

data class FindArtifactsQuery(
    val projectId: ProjectId? = null,
    val iterationId: IterationId? = null,
    val sourceProjectId: SourceProjectId? = null,
    val sourceIterationId: SourceIterationId? = null,
    val sourceDocumentId: SourceDocumentId? = null,
    val sourceTaskGraphId: SourceTaskGraphId? = null,
    val sourceTaskId: SourceTaskId? = null,
    val sourceRunId: SourceRunId? = null,
    val artifactType: ArtifactType? = null,
    val sourcePath: String? = null,
    val taskId: TaskId? = null,
    val runId: RunId? = null,
    val contentHash: ContentHash? = null,
    val sourceReference: SourceReference? = null,
    val limit: Int = 50,
    val cursor: String? = null,
) {
    init {
        require(limit > 0) { "FindArtifactsQuery limit must be positive" }
        require(cursor == null || cursor.isNotBlank()) { "FindArtifactsQuery cursor must not be blank" }
    }
}

data class KeywordSearchQuery(
    val query: String,
    val projectId: ProjectId? = null,
    val iterationId: IterationId? = null,
    val artifactType: ArtifactType? = null,
    val sourcePath: String? = null,
    val taskId: TaskId? = null,
    val runId: RunId? = null,
    val metadataFilters: Map<String, String> = emptyMap(),
    val limit: Int = 20,
    val cursor: String? = null,
) {
    init {
        require(query.isNotBlank()) { "KeywordSearchQuery query must not be blank" }
        require(metadataFilters.keys.all { it.isNotBlank() }) {
            "KeywordSearchQuery metadata filter keys must not be blank"
        }
        require(metadataFilters.values.all { it.isNotBlank() }) {
            "KeywordSearchQuery metadata filter values must not be blank"
        }
        require(limit > 0) { "KeywordSearchQuery limit must be positive" }
        require(cursor == null || cursor.isNotBlank()) { "KeywordSearchQuery cursor must not be blank" }
    }
}

data class VectorSearchQuery(
    val embedding: Embedding,
    val embeddingModel: String,
    val embeddingDimension: Int,
    val embeddingVersion: String,
    val distanceMetric: DistanceMetric,
    val projectId: ProjectId? = null,
    val iterationId: IterationId? = null,
    val artifactType: ArtifactType? = null,
    val sourcePath: String? = null,
    val taskId: TaskId? = null,
    val runId: RunId? = null,
    val metadataFilters: Map<String, String> = emptyMap(),
    val limit: Int = 20,
    val cursor: String? = null,
) {
    init {
        require(embedding.values.all { it.isFinite() }) { "VectorSearchQuery embedding values must be finite" }
        require(embeddingModel.isNotBlank()) { "VectorSearchQuery embeddingModel must not be blank" }
        require(embeddingDimension > 0) { "VectorSearchQuery embeddingDimension must be positive" }
        require(embeddingVersion.isNotBlank()) { "VectorSearchQuery embeddingVersion must not be blank" }
        require(metadataFilters.keys.all { it.isNotBlank() }) {
            "VectorSearchQuery metadata filter keys must not be blank"
        }
        require(metadataFilters.values.all { it.isNotBlank() }) {
            "VectorSearchQuery metadata filter values must not be blank"
        }
        require(limit > 0) { "VectorSearchQuery limit must be positive" }
        require(cursor == null || cursor.isNotBlank()) { "VectorSearchQuery cursor must not be blank" }
    }
}

data class HybridSearchQuery(
    val query: String,
    val embedding: Embedding,
    val embeddingModel: String,
    val embeddingDimension: Int,
    val embeddingVersion: String,
    val distanceMetric: DistanceMetric,
    val projectId: ProjectId? = null,
    val iterationId: IterationId? = null,
    val artifactType: ArtifactType? = null,
    val sourcePath: String? = null,
    val taskId: TaskId? = null,
    val runId: RunId? = null,
    val metadataFilters: Map<String, String> = emptyMap(),
    val rrfK: Int = DEFAULT_RRF_K,
    val candidateLimit: Int = DEFAULT_HYBRID_CANDIDATE_LIMIT,
    val limit: Int = 20,
    val cursor: String? = null,
) {
    init {
        require(query.isNotBlank()) { "HybridSearchQuery query must not be blank" }
        require(embedding.values.all { it.isFinite() }) { "HybridSearchQuery embedding values must be finite" }
        require(embeddingModel.isNotBlank()) { "HybridSearchQuery embeddingModel must not be blank" }
        require(embeddingDimension > 0) { "HybridSearchQuery embeddingDimension must be positive" }
        require(embeddingVersion.isNotBlank()) { "HybridSearchQuery embeddingVersion must not be blank" }
        require(metadataFilters.keys.all { it.isNotBlank() }) {
            "HybridSearchQuery metadata filter keys must not be blank"
        }
        require(metadataFilters.values.all { it.isNotBlank() }) {
            "HybridSearchQuery metadata filter values must not be blank"
        }
        require(rrfK > 0) { "HybridSearchQuery rrfK must be positive" }
        require(candidateLimit > 0) { "HybridSearchQuery candidateLimit must be positive" }
        require(limit > 0) { "HybridSearchQuery limit must be positive" }
        require(candidateLimit >= limit) { "HybridSearchQuery candidateLimit must be greater than or equal to limit" }
        require(cursor == null || cursor.isNotBlank()) { "HybridSearchQuery cursor must not be blank" }
    }
}

const val DEFAULT_RRF_K = 60
const val DEFAULT_HYBRID_CANDIDATE_LIMIT = 80
