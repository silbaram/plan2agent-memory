package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch

interface VectorSearchPort {
    fun search(query: VectorSearchQuery): PagedResult<VectorSearchMatch>

    /**
     * Internal active-profile search contract. Its exact set ID is resolved before the adapter
     * is called, so this query never needs a model/dimension/version tuple lookup.
     */
    fun search(query: ActiveVectorSearchQuery): PagedResult<VectorSearchMatch> =
        throw UnsupportedOperationException("Active vector search is not supported by this adapter")
}

data class ActiveVectorSearchQuery(
    val embeddingSetId: EmbeddingSetId,
    val embedding: Embedding,
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
        require(embedding.values.all { it.isFinite() }) {
            "ActiveVectorSearchQuery embedding values must be finite"
        }
        require(embedding.values.size == ACTIVE_VECTOR_DIMENSION) {
            "ActiveVectorSearchQuery embedding must have $ACTIVE_VECTOR_DIMENSION dimensions"
        }
        require(sourcePath == null || sourcePath.isNotBlank()) {
            "ActiveVectorSearchQuery sourcePath must not be blank when supplied"
        }
        require(metadataFilters.keys.all { it.isNotBlank() }) {
            "ActiveVectorSearchQuery metadata filter keys must not be blank"
        }
        require(metadataFilters.values.all { it.isNotBlank() }) {
            "ActiveVectorSearchQuery metadata filter values must not be blank"
        }
        require(limit > 0) { "ActiveVectorSearchQuery limit must be positive" }
        require(cursor == null || cursor.isNotBlank()) { "ActiveVectorSearchQuery cursor must not be blank" }
    }
}

const val ACTIVE_VECTOR_DIMENSION = 384
