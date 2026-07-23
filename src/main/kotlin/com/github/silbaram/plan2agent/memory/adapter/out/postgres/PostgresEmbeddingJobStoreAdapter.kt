package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingJobStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EnqueueEmbeddingJobCommand
import com.github.silbaram.plan2agent.memory.application.port.out.FindEmbeddingJobsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJob
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobErrorCode
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobFailure
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobStatus
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Repository
class PostgresEmbeddingJobStoreAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
) : EmbeddingJobStorePort {
    override fun enqueueMissing(command: EnqueueEmbeddingJobCommand): EmbeddingJob =
        metrics.recordWrite("embedding_job.enqueue") {
            jdbc.queryForObject(
                """
                INSERT INTO embedding_jobs (
                    embedding_job_id, chunk_id, embedding_set_id, status, attempt_count, next_attempt_at,
                    lease_generation, created_at, updated_at
                ) VALUES (
                    :embeddingJobId, :chunkId, :embeddingSetId, 'pending', 0, :enqueuedAt,
                    0, :enqueuedAt, NULL
                )
                ON CONFLICT (chunk_id, embedding_set_id) DO UPDATE SET
                    updated_at = embedding_jobs.updated_at
                RETURNING *
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("embeddingJobId", uuid(command.id.value))
                    .addValue("chunkId", uuid(command.chunkId.value))
                    .addValue("embeddingSetId", uuid(command.embeddingSetId.value))
                    .addValue("enqueuedAt", Timestamp.from(command.enqueuedAt)),
                embeddingJobMapper(),
            )!!
        }

    override fun claimDue(batchSize: Int, owner: String, leaseUntil: Instant): List<EmbeddingJob> =
        metrics.recordWrite("embedding_job.claim") {
            require(batchSize > 0) { "Embedding job batchSize must be positive" }
            require(owner.isNotBlank()) { "Embedding job owner must not be blank" }
            jdbc.query(
                """
                WITH next_job AS (
                    SELECT embedding_job_id
                    FROM embedding_jobs
                    WHERE status IN ('pending', 'retrying')
                      AND next_attempt_at <= now()
                    ORDER BY next_attempt_at, created_at, embedding_job_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                UPDATE embedding_jobs AS job
                SET status = 'running',
                    attempt_count = job.attempt_count + 1,
                    lease_owner = :owner,
                    lease_generation = job.lease_generation + 1,
                    lease_expires_at = :leaseUntil,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    started_at = COALESCE(job.started_at, now()),
                    last_attempt_at = now(),
                    updated_at = now()
                FROM next_job
                WHERE job.embedding_job_id = next_job.embedding_job_id
                RETURNING job.*
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("batchSize", batchSize)
                    .addValue("owner", owner)
                    .addValue("leaseUntil", Timestamp.from(leaseUntil)),
                embeddingJobMapper(),
            )
        }

    override fun releaseClaimToPending(
        jobId: EmbeddingJobId,
        owner: String,
        leaseGeneration: Long,
    ): EmbeddingJob? =
        metrics.recordWrite("embedding_job.release_claim") {
            jdbc.queryOne(
                """
                UPDATE embedding_jobs
                SET status = 'pending',
                    next_attempt_at = now(),
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    updated_at = now()
                WHERE embedding_job_id = :embeddingJobId
                  AND status = 'running'
                  AND lease_owner = :owner
                  AND lease_generation = :leaseGeneration
                  AND lease_expires_at > now()
                RETURNING *
                """.trimIndent(),
                casParams(jobId, owner, leaseGeneration),
                embeddingJobMapper(),
            )
        }

    override fun markSucceeded(
        jobId: EmbeddingJobId,
        owner: String,
        leaseGeneration: Long,
    ): EmbeddingJob? =
        metrics.recordWrite("embedding_job.mark_succeeded") {
            jdbc.queryOne(
                """
                UPDATE embedding_jobs
                SET status = 'succeeded',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    updated_at = now(),
                    completed_at = now()
                WHERE embedding_job_id = :embeddingJobId
                  AND status = 'running'
                  AND lease_owner = :owner
                  AND lease_generation = :leaseGeneration
                  AND lease_expires_at > now()
                RETURNING *
                """.trimIndent(),
                casParams(jobId, owner, leaseGeneration),
                embeddingJobMapper(),
            )
        }

    override fun markRetrying(
        jobId: EmbeddingJobId,
        owner: String,
        leaseGeneration: Long,
        failure: EmbeddingJobFailure,
        nextAttemptAt: Instant,
    ): EmbeddingJob? =
        metrics.recordWrite("embedding_job.schedule_retry") {
            jdbc.queryOne(
                """
                UPDATE embedding_jobs
                SET status = 'retrying',
                    next_attempt_at = :nextAttemptAt,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    last_error_code = :errorCode,
                    last_error_message = :errorMessage,
                    updated_at = now()
                WHERE embedding_job_id = :embeddingJobId
                  AND status = 'running'
                  AND lease_owner = :owner
                  AND lease_generation = :leaseGeneration
                  AND lease_expires_at > now()
                RETURNING *
                """.trimIndent(),
                casParams(jobId, owner, leaseGeneration)
                    .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                    .addValue("errorCode", failure.code.toDbValue())
                    .addValue("errorMessage", failure.message),
                embeddingJobMapper(),
            )
        }

    override fun markPermanentlyFailed(
        jobId: EmbeddingJobId,
        owner: String,
        leaseGeneration: Long,
        failure: EmbeddingJobFailure,
    ): EmbeddingJob? =
        metrics.recordWrite("embedding_job.mark_permanently_failed") {
            jdbc.queryOne(
                """
                UPDATE embedding_jobs
                SET status = 'permanently_failed',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    last_error_code = :errorCode,
                    last_error_message = :errorMessage,
                    updated_at = now(),
                    completed_at = now()
                WHERE embedding_job_id = :embeddingJobId
                  AND status = 'running'
                  AND lease_owner = :owner
                  AND lease_generation = :leaseGeneration
                  AND lease_expires_at > now()
                RETURNING *
                """.trimIndent(),
                casParams(jobId, owner, leaseGeneration)
                    .addValue("errorCode", failure.code.toDbValue())
                    .addValue("errorMessage", failure.message),
                embeddingJobMapper(),
            )
        }

    override fun recoverExpiredLeases(): List<EmbeddingJob> =
        metrics.recordWrite("embedding_job.recover_expired") {
            jdbc.query(
                """
                UPDATE embedding_jobs
                SET status = 'pending',
                    next_attempt_at = now(),
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    last_error_code = 'lease_expired',
                    last_error_message = 'Lease expired before job completion',
                    updated_at = now()
                WHERE status = 'running'
                  AND lease_expires_at <= now()
                RETURNING *
                """.trimIndent(),
                MapSqlParameterSource(),
                embeddingJobMapper(),
            )
        }

    override fun findById(id: EmbeddingJobId): EmbeddingJob? =
        metrics.recordSearch("embedding_job.find") {
            jdbc.queryOne(
                "SELECT * FROM embedding_jobs WHERE embedding_job_id = :embeddingJobId",
                MapSqlParameterSource("embeddingJobId", uuid(id.value)),
                embeddingJobMapper(),
            )
        }

    override fun findPage(query: FindEmbeddingJobsQuery): PagedResult<EmbeddingJob> =
        metrics.recordSearch("embedding_job.page") {
            val cursor = query.cursor?.let(::decodeCursor)
            val conditions = mutableListOf<String>()
            val params = MapSqlParameterSource().addValue("limitPlusOne", query.limit + 1)

            if (query.statuses.isNotEmpty()) {
                conditions += "status IN (:statuses)"
                params.addValue("statuses", query.statuses.map { it.toDbValue() })
            }
            if (query.chunkId != null) {
                conditions += "chunk_id = :chunkId"
                params.addValue("chunkId", uuid(query.chunkId.value))
            }
            if (cursor != null) {
                conditions += """
                    (created_at < :cursorCreatedAt OR (
                        created_at = :cursorCreatedAt AND embedding_job_id < :cursorEmbeddingJobId
                    ))
                """.trimIndent()
                params
                    .addValue("cursorCreatedAt", Timestamp.from(cursor.createdAt))
                    .addValue("cursorEmbeddingJobId", cursor.id)
            }

            val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
            val rows = jdbc.query(
                """
                SELECT *
                FROM embedding_jobs
                $whereClause
                ORDER BY created_at DESC, embedding_job_id DESC
                LIMIT :limitPlusOne
                """.trimIndent(),
                params,
                embeddingJobMapper(),
            )
            rows.toPagedResult(query.limit)
        }

    override fun retryFailed(jobId: EmbeddingJobId): EmbeddingJob? =
        metrics.recordWrite("embedding_job.retry") {
            jdbc.queryOne(
                """
                WITH retried_job AS (
                    UPDATE embedding_jobs
                    SET status = 'pending',
                        attempt_count = 0,
                        next_attempt_at = now(),
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        last_error_code = NULL,
                        last_error_message = NULL,
                        updated_at = now(),
                        completed_at = NULL
                    WHERE embedding_job_id = :embeddingJobId
                      AND status = 'permanently_failed'
                    RETURNING *
                )
                SELECT * FROM retried_job
                UNION ALL
                SELECT *
                FROM embedding_jobs
                WHERE embedding_job_id = :embeddingJobId
                  AND NOT EXISTS (SELECT 1 FROM retried_job)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("embeddingJobId", uuid(jobId.value)),
                embeddingJobMapper(),
            )
        }

    private fun casParams(
        jobId: EmbeddingJobId,
        owner: String,
        leaseGeneration: Long,
    ): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("embeddingJobId", uuid(jobId.value))
            .addValue("owner", owner)
            .addValue("leaseGeneration", leaseGeneration)
}

private fun embeddingJobMapper(): RowMapper<EmbeddingJob> =
    RowMapper { rs, _ ->
        val errorCode = rs.getString("last_error_code")
        val errorMessage = rs.getString("last_error_message")
        EmbeddingJob(
            id = EmbeddingJobId(rs.getString("embedding_job_id")),
            chunkId = DocumentChunkId(rs.getString("chunk_id")),
            embeddingSetId = EmbeddingSetId(rs.getString("embedding_set_id")),
            status = embeddingJobStatusFromDbValue(rs.getString("status")),
            attemptCount = rs.getInt("attempt_count"),
            nextAttemptAt = rs.instant("next_attempt_at"),
            leaseOwner = rs.getString("lease_owner"),
            leaseGeneration = rs.getLong("lease_generation"),
            leaseExpiresAt = rs.nullableInstant("lease_expires_at"),
            lastError = errorCode?.let {
                EmbeddingJobFailure.fromStoredMessage(
                    embeddingJobErrorCodeFromDbValue(it),
                    requireNotNull(errorMessage) { "Embedding job error message is missing" },
                )
            },
            createdAt = rs.instant("created_at"),
            updatedAt = rs.nullableInstant("updated_at"),
            startedAt = rs.nullableInstant("started_at"),
            lastAttemptAt = rs.nullableInstant("last_attempt_at"),
            completedAt = rs.nullableInstant("completed_at"),
        )
    }

private fun EmbeddingJobStatus.toDbValue(): String =
    when (this) {
        EmbeddingJobStatus.PENDING -> "pending"
        EmbeddingJobStatus.RUNNING -> "running"
        EmbeddingJobStatus.RETRYING -> "retrying"
        EmbeddingJobStatus.SUCCEEDED -> "succeeded"
        EmbeddingJobStatus.PERMANENTLY_FAILED -> "permanently_failed"
    }

private fun embeddingJobStatusFromDbValue(value: String): EmbeddingJobStatus =
    when (value) {
        "pending" -> EmbeddingJobStatus.PENDING
        "running" -> EmbeddingJobStatus.RUNNING
        "retrying" -> EmbeddingJobStatus.RETRYING
        "succeeded" -> EmbeddingJobStatus.SUCCEEDED
        "permanently_failed" -> EmbeddingJobStatus.PERMANENTLY_FAILED
        else -> error("Unsupported embedding job status: $value")
    }

private fun EmbeddingJobErrorCode.toDbValue(): String =
    when (this) {
        EmbeddingJobErrorCode.PROVIDER_UNAVAILABLE -> "provider_unavailable"
        EmbeddingJobErrorCode.PROVIDER_CONTRACT_INVALID -> "provider_contract_invalid"
        EmbeddingJobErrorCode.CONTENT_INVALID -> "content_invalid"
        EmbeddingJobErrorCode.MAX_ATTEMPTS_EXHAUSTED -> "max_attempts_exhausted"
        EmbeddingJobErrorCode.LEASE_EXPIRED -> "lease_expired"
    }

private fun embeddingJobErrorCodeFromDbValue(value: String): EmbeddingJobErrorCode =
    when (value) {
        "provider_unavailable" -> EmbeddingJobErrorCode.PROVIDER_UNAVAILABLE
        "provider_contract_invalid" -> EmbeddingJobErrorCode.PROVIDER_CONTRACT_INVALID
        "content_invalid" -> EmbeddingJobErrorCode.CONTENT_INVALID
        "max_attempts_exhausted" -> EmbeddingJobErrorCode.MAX_ATTEMPTS_EXHAUSTED
        "lease_expired" -> EmbeddingJobErrorCode.LEASE_EXPIRED
        else -> error("Unsupported embedding job error code: $value")
    }

private data class EmbeddingJobCursor(
    val createdAt: Instant,
    val id: UUID,
)

private fun List<EmbeddingJob>.toPagedResult(limit: Int): PagedResult<EmbeddingJob> {
    val pageItems = take(limit)
    val nextCursor = if (size > limit) {
        val last = requireNotNull(pageItems.lastOrNull())
        encodeCursor(EmbeddingJobCursor(last.createdAt, uuid(last.id.value)))
    } else {
        null
    }
    return PagedResult(items = pageItems, nextCursor = nextCursor)
}

private fun encodeCursor(cursor: EmbeddingJobCursor): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        "${cursor.createdAt}|${cursor.id}".toByteArray(StandardCharsets.UTF_8),
    )

private fun decodeCursor(cursor: String): EmbeddingJobCursor =
    try {
        val decoded = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        val parts = decoded.split('|')
        require(parts.size == 2) { "Embedding job cursor has an invalid format" }
        EmbeddingJobCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]))
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("Embedding job cursor is invalid", failure)
    }

private fun <T> NamedParameterJdbcTemplate.queryOne(
    sql: String,
    params: MapSqlParameterSource,
    mapper: RowMapper<T>,
): T? =
    try {
        queryForObject(sql, params, mapper)
    } catch (_: EmptyResultDataAccessException) {
        null
    }

private fun uuid(value: String): UUID = UUID.fromString(value)

private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

private fun ResultSet.nullableInstant(column: String): Instant? = getTimestamp(column)?.toInstant()
