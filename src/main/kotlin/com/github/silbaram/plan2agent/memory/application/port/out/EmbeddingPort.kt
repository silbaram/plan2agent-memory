package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile

/** Application-owned boundary for all server-managed embedding providers. */
interface EmbeddingPort {
    val activeEmbeddingTarget: ActiveEmbeddingTarget
    val providerState: EmbeddingProviderState

    val immutableProfile: V2EmbeddingProfile
        get() = activeEmbeddingTarget.profile

    val activeEmbeddingSetId: EmbeddingSetId?
        get() = activeEmbeddingTarget.embeddingSetId

    fun embedDocuments(documents: List<String>): List<EmbeddingResult>

    fun embedQuery(query: String): EmbeddingResult
}

data class ActiveEmbeddingTarget(
    val profile: V2EmbeddingProfile,
    val embeddingSetId: EmbeddingSetId? = null,
)

data class EmbeddingResult(
    val target: ActiveEmbeddingTarget,
    val embedding: Embedding,
)

enum class EmbeddingProviderState {
    NOT_CONFIGURED,
    INITIALIZING,
    READY,
    UNAVAILABLE,
}

open class EmbeddingProviderException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean,
) : RuntimeException(message, cause)

class ProviderNotConfiguredException : EmbeddingProviderException(
    message = "No embedding provider is configured",
    retryable = false,
)

class ProviderUnavailableException(
    message: String = "Embedding provider is unavailable",
    cause: Throwable? = null,
    retryable: Boolean = true,
) : EmbeddingProviderException(message, cause, retryable)
