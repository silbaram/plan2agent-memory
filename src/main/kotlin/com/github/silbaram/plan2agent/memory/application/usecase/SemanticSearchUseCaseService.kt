package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.`in`.SemanticSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveVectorSearchQuery
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderUnavailableException
import com.github.silbaram.plan2agent.memory.application.port.out.VectorSearchPort
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SemanticSearchUseCaseService(
    private val embeddingPort: EmbeddingPort,
    private val vectorSearchPort: VectorSearchPort,
) : SemanticSearchUseCase {
    @Transactional(readOnly = true)
    override fun semanticSearch(query: SemanticSearchQuery): PagedResult<VectorSearchMatch> {
        validateQuery(query)
        return semanticSearch(query, requireReadyTarget())
    }

    override fun activeEmbeddingSetId(): EmbeddingSetId =
        requireNotNull(requireReadyTarget().embeddingSetId) { "Active embedding set must be available" }

    @Transactional(readOnly = true)
    override fun semanticSearch(
        query: SemanticSearchQuery,
        expectedActiveEmbeddingSetId: EmbeddingSetId,
    ): PagedResult<VectorSearchMatch> {
        val target = requireReadyTarget()
        require(target.embeddingSetId == expectedActiveEmbeddingSetId) {
            "active embedding target changed during search"
        }
        return semanticSearch(query, target)
    }

    private fun semanticSearch(
        query: SemanticSearchQuery,
        target: ActiveEmbeddingTarget,
    ): PagedResult<VectorSearchMatch> {
        validateQuery(query)
        val activeEmbeddingSetId = requireNotNull(target.embeddingSetId) { "Active embedding set must be available" }
        val requestFingerprint = SearchRequestCursor.semanticFingerprint(query, activeEmbeddingSetId)
        val continuationKey = query.cursor?.let { SearchRequestCursor.decode(it, requestFingerprint) }
        val embedding = embedQuery(query.query)

        if (embedding.target != target || !isUsableEmbedding(embedding.embedding, target)) {
            throw ProviderUnavailableException()
        }

        return vectorSearchPort.search(
            ActiveVectorSearchQuery(
                embeddingSetId = activeEmbeddingSetId,
                embedding = embedding.embedding,
                projectId = query.projectId,
                iterationId = query.iterationId,
                artifactType = query.artifactType,
                sourcePath = query.sourcePath,
                taskId = query.taskId,
                runId = query.runId,
                metadataFilters = query.metadataFilters,
                limit = query.limit,
                cursor = continuationKey,
            ),
        )
            .let { result ->
                PagedResult(
                    items = result.items,
                    nextCursor = result.nextCursor?.let { SearchRequestCursor.encode(requestFingerprint, it) },
                )
            }
    }

    private fun validateQuery(query: SemanticSearchQuery) {
        require(query.query.isNotBlank()) { "SemanticSearchQuery query must not be blank" }
        require(query.sourcePath == null || query.sourcePath.isNotBlank()) {
            "SemanticSearchQuery sourcePath must not be blank when supplied"
        }
        require(query.metadataFilters.keys.all { it.isNotBlank() }) {
            "SemanticSearchQuery metadata filter keys must not be blank"
        }
        require(query.metadataFilters.values.all { it.isNotBlank() }) {
            "SemanticSearchQuery metadata filter values must not be blank"
        }
        require(query.limit > 0) { "SemanticSearchQuery limit must be positive" }
        require(query.cursor == null || query.cursor.isNotBlank()) {
            "SemanticSearchQuery cursor must not be blank when supplied"
        }
    }

    private fun requireReadyTarget(): ActiveEmbeddingTarget = when (embeddingPort.providerState) {
        EmbeddingProviderState.NOT_CONFIGURED -> throw ProviderNotConfiguredException()
        EmbeddingProviderState.INITIALIZING,
        EmbeddingProviderState.UNAVAILABLE,
        -> throw ProviderUnavailableException()
        EmbeddingProviderState.READY -> embeddingPort.activeEmbeddingTarget.also {
            if (it.embeddingSetId == null) {
                throw ProviderUnavailableException()
            }
        }
    }

    private fun embedQuery(query: String) = try {
        embeddingPort.embedQuery(query)
    } catch (failure: ProviderNotConfiguredException) {
        throw failure
    } catch (failure: ProviderUnavailableException) {
        throw failure
    } catch (failure: RuntimeException) {
        throw ProviderUnavailableException(cause = failure)
    }

    private fun isUsableEmbedding(embedding: Embedding, target: ActiveEmbeddingTarget): Boolean =
        embedding.values.size == target.profile.dimension && embedding.values.all { it.isFinite() }
}
