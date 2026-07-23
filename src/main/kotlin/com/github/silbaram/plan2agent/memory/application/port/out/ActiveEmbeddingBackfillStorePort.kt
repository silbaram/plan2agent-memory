package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import java.time.Instant

/**
 * Persists one bounded reconciliation pass for one already-selected active embedding set.
 *
 * The caller owns selecting the active target. This boundary deliberately has no operation for
 * creating embedding sets or changing the active pointer.
 */
interface ActiveEmbeddingBackfillStorePort {
    fun reconcile(
        embeddingSetId: EmbeddingSetId,
        batchSize: Int,
        afterChunkId: DocumentChunkId?,
        enqueuedAt: Instant,
    ): ActiveEmbeddingBackfillResult

    fun coverage(embeddingSetId: EmbeddingSetId): ActiveEmbeddingCoverage
}

data class ActiveEmbeddingBackfillResult(
    val scannedChunks: Int,
    val repairedTypedMirrors: Int,
    val enqueuedJobs: Int,
    val nextChunkId: DocumentChunkId?,
) {
    init {
        require(scannedChunks >= 0) { "Backfill scannedChunks must not be negative" }
        require(repairedTypedMirrors >= 0) { "Backfill repairedTypedMirrors must not be negative" }
        require(enqueuedJobs >= 0) { "Backfill enqueuedJobs must not be negative" }
    }
}

data class ActiveEmbeddingCoverage(
    val embeddingSetId: EmbeddingSetId,
    val eligibleTotal: Long,
    val pending: Long,
    val running: Long,
    val retrying: Long,
    val succeeded: Long,
    val permanentlyFailed: Long,
    val missing: Long,
) {
    init {
        val states = listOf(pending, running, retrying, succeeded, permanentlyFailed, missing)
        require(eligibleTotal >= 0 && states.all { it >= 0 }) { "Embedding coverage counts must not be negative" }
        require(states.sum() == eligibleTotal) { "Embedding coverage states must equal eligibleTotal" }
    }
}
