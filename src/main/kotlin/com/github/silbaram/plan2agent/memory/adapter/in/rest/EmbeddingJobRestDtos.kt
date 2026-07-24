package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.port.out.FindEmbeddingJobsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.DEFAULT_EMBEDDING_JOB_PAGE_LIMIT
import com.github.silbaram.plan2agent.memory.application.usecase.MAX_EMBEDDING_JOB_PAGE_LIMIT
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJob
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobErrorCode
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobFailure
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobStatus
import java.time.Instant

data class EmbeddingJobListRequest(
    val statuses: List<String> = emptyList(),
    val chunkId: String? = null,
    val limit: Int? = null,
    val cursor: String? = null,
)

data class EmbeddingJobResponse(
    val jobId: String,
    val chunkId: String,
    val embeddingSetId: String,
    val status: String,
    val attemptCount: Int,
    val nextAttemptAt: Instant,
    val leaseExpiresAt: Instant? = null,
    val lastErrorCode: String? = null,
    val sanitizedLastErrorMessage: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val completedAt: Instant? = null,
)

fun EmbeddingJobListRequest.toQuery(): FindEmbeddingJobsQuery {
    val resolvedLimit = limit ?: DEFAULT_EMBEDDING_JOB_PAGE_LIMIT
    require(resolvedLimit in 1..MAX_EMBEDDING_JOB_PAGE_LIMIT) {
        "limit must be between 1 and $MAX_EMBEDDING_JOB_PAGE_LIMIT"
    }
    return FindEmbeddingJobsQuery(
        statuses = statuses
            .flatMap { value -> value.split(',') }
            .map(::parseEmbeddingJobStatus)
            .toSet(),
        chunkId = chunkId?.trim()?.takeIf(String::isNotEmpty)?.let(::DocumentChunkId),
        limit = resolvedLimit,
        cursor = cursor?.trim()?.takeIf(String::isNotEmpty),
    )
}

fun EmbeddingJob.toResponse(): EmbeddingJobResponse =
    EmbeddingJobResponse(
        jobId = id.value,
        chunkId = chunkId.value,
        embeddingSetId = embeddingSetId.value,
        status = status.name.lowercase(),
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        leaseExpiresAt = leaseExpiresAt,
        lastErrorCode = lastError?.code?.toRestCode(),
        sanitizedLastErrorMessage = lastError?.code?.let(EmbeddingJobFailure::stableMessage),
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )

private fun parseEmbeddingJobStatus(value: String): EmbeddingJobStatus =
    try {
        EmbeddingJobStatus.valueOf(value.trim().uppercase())
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("status has invalid value")
    }

private fun EmbeddingJobErrorCode.toRestCode(): String =
    name.lowercase()
