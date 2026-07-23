package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJob
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobFailure
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobStatus
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import java.time.Instant

/** Durable queue boundary for embedding work. It deliberately does not run a worker loop. */
interface EmbeddingJobStorePort {
    /**
     * Creates the missing target job, or returns its current row when this chunk and embedding set are
     * already queued, leased, retried, or terminal. It never resets an existing job.
     */
    fun enqueueMissing(command: EnqueueEmbeddingJobCommand): EmbeddingJob

    fun claimDue(batchSize: Int, owner: String, leaseUntil: Instant): List<EmbeddingJob>

    fun releaseClaimToPending(
        jobId: EmbeddingJobId,
        owner: String,
        leaseGeneration: Long,
    ): EmbeddingJob?

    fun markRetrying(
        jobId: EmbeddingJobId,
        owner: String,
        leaseGeneration: Long,
        failure: EmbeddingJobFailure,
        nextAttemptAt: Instant,
    ): EmbeddingJob?

    fun markSucceeded(
        jobId: EmbeddingJobId,
        owner: String,
        leaseGeneration: Long,
    ): EmbeddingJob?

    fun markPermanentlyFailed(
        jobId: EmbeddingJobId,
        owner: String,
        leaseGeneration: Long,
        failure: EmbeddingJobFailure,
    ): EmbeddingJob?

    fun recoverExpiredLeases(): List<EmbeddingJob>

    fun findById(id: EmbeddingJobId): EmbeddingJob?

    fun findPage(query: FindEmbeddingJobsQuery): PagedResult<EmbeddingJob>

    /**
     * Resets a permanently failed job to pending, or returns the current row unchanged when it is
     * already pending, running, retrying, or succeeded. A null result means the job does not exist.
     */
    fun retryFailed(jobId: EmbeddingJobId): EmbeddingJob?
}

data class EnqueueEmbeddingJobCommand(
    val id: EmbeddingJobId,
    val chunkId: DocumentChunkId,
    val embeddingSetId: EmbeddingSetId,
    val enqueuedAt: Instant,
)

data class FindEmbeddingJobsQuery(
    val chunkId: DocumentChunkId? = null,
    val statuses: Set<EmbeddingJobStatus> = emptySet(),
    val limit: Int = 50,
    val cursor: String? = null,
) {
    init {
        require(limit > 0) { "FindEmbeddingJobsQuery limit must be positive" }
        require(cursor == null || cursor.isNotBlank()) { "FindEmbeddingJobsQuery cursor must not be blank" }
    }
}
