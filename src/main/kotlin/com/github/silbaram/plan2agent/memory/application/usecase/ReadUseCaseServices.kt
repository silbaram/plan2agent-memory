package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactsUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.KeywordSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.VectorSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactQueryPort
import com.github.silbaram.plan2agent.memory.application.port.out.KeywordSearchPort
import com.github.silbaram.plan2agent.memory.application.port.out.VectorSearchPort
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
import com.github.silbaram.plan2agent.memory.domain.KeywordSearchMatch
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReadUseCaseService(
    private val artifactQueryPort: ArtifactQueryPort,
    private val keywordSearchPort: KeywordSearchPort,
    private val vectorSearchPort: VectorSearchPort,
) : FindArtifactsUseCase,
    KeywordSearchUseCase,
    VectorSearchUseCase {

    @Transactional(readOnly = true)
    override fun findArtifacts(query: FindArtifactsQuery): PagedResult<ArtifactSummary> {
        validateLimit(query.limit, "FindArtifactsQuery")
        validateOptionalSourcePath(query.sourcePath, "FindArtifactsQuery")
        validateOptionalCursor(query.cursor, "FindArtifactsQuery")
        return artifactQueryPort.findArtifacts(query)
    }

    @Transactional(readOnly = true)
    override fun keywordSearch(query: KeywordSearchQuery): PagedResult<KeywordSearchMatch> {
        require(query.query.isNotBlank()) { "KeywordSearchQuery query must not be blank" }
        validateLimit(query.limit, "KeywordSearchQuery")
        validateOptionalSourcePath(query.sourcePath, "KeywordSearchQuery")
        validateMetadataFilters(query.metadataFilters, "KeywordSearchQuery")
        validateOptionalCursor(query.cursor, "KeywordSearchQuery")
        return keywordSearchPort.search(query)
    }

    @Transactional(readOnly = true)
    override fun vectorSearch(query: VectorSearchQuery): PagedResult<VectorSearchMatch> {
        require(query.embedding.values.isNotEmpty()) { "VectorSearchQuery embedding must not be empty" }
        require(query.embedding.values.all { it.isFinite() }) { "VectorSearchQuery embedding values must be finite" }
        require(query.embeddingModel.isNotBlank()) { "VectorSearchQuery embeddingModel must not be blank" }
        require(query.embeddingDimension > 0) { "VectorSearchQuery embeddingDimension must be positive" }
        require(query.embedding.values.size == query.embeddingDimension) {
            "VectorSearchQuery embeddingDimension must match embedding size"
        }
        require(query.embeddingVersion.isNotBlank()) { "VectorSearchQuery embeddingVersion must not be blank" }
        validateLimit(query.limit, "VectorSearchQuery")
        validateOptionalSourcePath(query.sourcePath, "VectorSearchQuery")
        validateMetadataFilters(query.metadataFilters, "VectorSearchQuery")
        validateOptionalCursor(query.cursor, "VectorSearchQuery")
        return vectorSearchPort.search(query)
    }

    private fun validateLimit(limit: Int, label: String) {
        require(limit > 0) { "$label limit must be positive" }
    }

    private fun validateOptionalSourcePath(sourcePath: String?, label: String) {
        sourcePath?.let {
            require(it.isNotBlank()) { "$label sourcePath must not be blank when supplied" }
        }
    }

    private fun validateMetadataFilters(metadataFilters: Map<String, String>, label: String) {
        require(metadataFilters.keys.all { it.isNotBlank() }) { "$label metadata filter keys must not be blank" }
        require(metadataFilters.values.all { it.isNotBlank() }) { "$label metadata filter values must not be blank" }
    }

    private fun validateOptionalCursor(cursor: String?, label: String) {
        cursor?.let {
            require(it.isNotBlank()) { "$label cursor must not be blank when supplied" }
        }
    }
}
