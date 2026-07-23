package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId

/**
 * Resolves the immutable, server-global V2 embedding target for a configured provider.
 *
 * Implementations may bootstrap the target only when no server-global profile state exists.
 * This boundary intentionally has no pointer mutation operation.
 */
fun interface ActiveEmbeddingProfileResolver {
    fun resolveActiveV2EmbeddingSetId(): EmbeddingSetId
}

class ActiveEmbeddingProfileResolutionException(
    cause: Throwable? = null,
) : RuntimeException("Active embedding profile resolution failed", cause)
