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

/**
 * Reads the persisted server-global target for asynchronous embedding work.
 *
 * Unlike [ActiveEmbeddingProfileResolver], this boundary never compares the target with a
 * currently loaded provider. It bootstraps only a completely empty profile state; any partial,
 * corrupt, or dangling state fails closed so a caller cannot enqueue work for an ambiguous target.
 */
fun interface PersistedActiveEmbeddingSetResolver {
    fun requirePersistedActiveV2EmbeddingSetId(): EmbeddingSetId
}

/**
 * Reads only an existing, structurally valid fixed V2 target for backfill and coverage.
 *
 * Unlike the bootstrap-capable resolvers used by the write and provider lifecycle paths, this
 * boundary returns null for an absent, partial, dangling, or mismatched pointer. It intentionally
 * exposes no set, pointer, or cutover mutation operation.
 */
fun interface StructurallyValidPersistedActiveEmbeddingSetResolver {
    fun findStructurallyValidActiveV2EmbeddingSetId(): EmbeddingSetId?
}

class ActiveEmbeddingProfileResolutionException(
    cause: Throwable? = null,
) : RuntimeException("Active embedding profile resolution failed", cause)

class PersistedActiveEmbeddingSetResolutionException(
    cause: Throwable? = null,
) : RuntimeException("Persisted active embedding set resolution failed", cause)
