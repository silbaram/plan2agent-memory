package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingBackfillResult
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingBackfillStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingCoverage
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant

@Repository
class PostgresActiveEmbeddingBackfillStoreAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    transactionManager: PlatformTransactionManager,
) : ActiveEmbeddingBackfillStorePort {
    private val transactions = TransactionTemplate(transactionManager)

    override fun reconcile(
        embeddingSetId: EmbeddingSetId,
        batchSize: Int,
        afterChunkId: DocumentChunkId?,
        enqueuedAt: Instant,
    ): ActiveEmbeddingBackfillResult =
        metrics.recordWrite("embedding_backfill.reconcile") {
            require(batchSize > 0) { "Embedding backfill batchSize must be positive" }
            requireNotNull(transactions.execute<ActiveEmbeddingBackfillResult> {
                jdbc.queryForObject(
                    """
                    WITH candidates AS MATERIALIZED (
                        SELECT document_chunk.chunk_id
                        FROM document_chunks AS document_chunk
                        LEFT JOIN chunk_embeddings AS chunk_embedding
                            ON chunk_embedding.chunk_id = document_chunk.chunk_id
                           AND chunk_embedding.embedding_set_id = CAST(:embeddingSetId AS uuid)
                        LEFT JOIN chunk_embedding_vectors_384 AS typed_vector
                            ON typed_vector.chunk_embedding_id = chunk_embedding.chunk_embedding_id
                        LEFT JOIN embedding_jobs AS embedding_job
                            ON embedding_job.chunk_id = document_chunk.chunk_id
                           AND embedding_job.embedding_set_id = CAST(:embeddingSetId AS uuid)
                        WHERE (
                            (chunk_embedding.chunk_embedding_id IS NULL AND embedding_job.embedding_job_id IS NULL)
                            OR (
                                chunk_embedding.chunk_embedding_id IS NOT NULL
                                AND typed_vector.chunk_embedding_id IS NULL
                                AND vector_dims(chunk_embedding.embedding) = 384
                            )
                        )
                          AND (
                              CAST(:afterChunkId AS uuid) IS NULL
                              OR document_chunk.chunk_id > CAST(:afterChunkId AS uuid)
                          )
                        ORDER BY document_chunk.chunk_id
                        LIMIT :batchSize
                    ),
                    repaired AS (
                        INSERT INTO chunk_embedding_vectors_384 (chunk_embedding_id, embedding)
                        SELECT chunk_embedding.chunk_embedding_id, chunk_embedding.embedding::vector(384)
                        FROM candidates
                        JOIN chunk_embeddings AS chunk_embedding
                            ON chunk_embedding.chunk_id = candidates.chunk_id
                           AND chunk_embedding.embedding_set_id = CAST(:embeddingSetId AS uuid)
                        LEFT JOIN chunk_embedding_vectors_384 AS typed_vector
                            ON typed_vector.chunk_embedding_id = chunk_embedding.chunk_embedding_id
                        WHERE typed_vector.chunk_embedding_id IS NULL
                          AND vector_dims(chunk_embedding.embedding) = 384
                        ON CONFLICT (chunk_embedding_id) DO NOTHING
                        RETURNING 1
                    ),
                    enqueued AS (
                        INSERT INTO embedding_jobs (
                            embedding_job_id, chunk_id, embedding_set_id, status, attempt_count,
                            next_attempt_at, lease_generation, created_at, updated_at
                        )
                        SELECT
                            (
                                substr(md5('embedding-job:' || candidates.chunk_id::text || ':' || :embeddingSetId), 1, 8) || '-' ||
                                substr(md5('embedding-job:' || candidates.chunk_id::text || ':' || :embeddingSetId), 9, 4) || '-3' ||
                                substr(md5('embedding-job:' || candidates.chunk_id::text || ':' || :embeddingSetId), 14, 3) || '-8' ||
                                substr(md5('embedding-job:' || candidates.chunk_id::text || ':' || :embeddingSetId), 18, 3) || '-' ||
                                substr(md5('embedding-job:' || candidates.chunk_id::text || ':' || :embeddingSetId), 21, 12)
                            )::uuid,
                            candidates.chunk_id,
                            CAST(:embeddingSetId AS uuid),
                            'pending',
                            0,
                            :enqueuedAt,
                            0,
                            :enqueuedAt,
                            NULL
                        FROM candidates
                        LEFT JOIN chunk_embeddings AS chunk_embedding
                            ON chunk_embedding.chunk_id = candidates.chunk_id
                           AND chunk_embedding.embedding_set_id = CAST(:embeddingSetId AS uuid)
                        LEFT JOIN embedding_jobs AS embedding_job
                            ON embedding_job.chunk_id = candidates.chunk_id
                           AND embedding_job.embedding_set_id = CAST(:embeddingSetId AS uuid)
                        WHERE chunk_embedding.chunk_embedding_id IS NULL
                          AND embedding_job.embedding_job_id IS NULL
                        ON CONFLICT (chunk_id, embedding_set_id) DO NOTHING
                        RETURNING 1
                    ),
                    last_candidate AS (
                        SELECT chunk_id::text AS chunk_id
                        FROM candidates
                        ORDER BY chunk_id DESC
                        LIMIT 1
                    )
                    SELECT
                        (SELECT count(*) FROM candidates) AS scanned_chunks,
                        (SELECT count(*) FROM repaired) AS repaired_typed_mirrors,
                        (SELECT count(*) FROM enqueued) AS enqueued_jobs,
                        (SELECT chunk_id FROM last_candidate) AS next_chunk_id
                    """.trimIndent(),
                    MapSqlParameterSource()
                        .addValue("embeddingSetId", embeddingSetId.value)
                        .addValue("afterChunkId", afterChunkId?.value)
                        .addValue("batchSize", batchSize)
                        .addValue("enqueuedAt", Timestamp.from(enqueuedAt)),
                ) { row, _ ->
                    ActiveEmbeddingBackfillResult(
                        scannedChunks = row.getInt("scanned_chunks"),
                        repairedTypedMirrors = row.getInt("repaired_typed_mirrors"),
                        enqueuedJobs = row.getInt("enqueued_jobs"),
                        nextChunkId = row.getString("next_chunk_id")?.let(::DocumentChunkId),
                    )
                }!!
            })
        }

    override fun coverage(embeddingSetId: EmbeddingSetId): ActiveEmbeddingCoverage =
        metrics.recordSearch("embedding_backfill.coverage") {
            jdbc.queryForObject(
                """
                SELECT
                    count(*) AS eligible_total,
                    count(*) FILTER (
                        WHERE embedding_job.status = 'pending'
                    ) AS pending,
                    count(*) FILTER (
                        WHERE embedding_job.status = 'running'
                    ) AS running,
                    count(*) FILTER (
                        WHERE embedding_job.status = 'retrying'
                    ) AS retrying,
                    count(*) FILTER (
                        WHERE embedding_job.status = 'succeeded'
                           OR (
                               embedding_job.embedding_job_id IS NULL
                               AND chunk_embedding.chunk_embedding_id IS NOT NULL
                           )
                    ) AS succeeded,
                    count(*) FILTER (
                        WHERE embedding_job.status = 'permanently_failed'
                    ) AS permanently_failed,
                    count(*) FILTER (
                        WHERE embedding_job.embedding_job_id IS NULL
                          AND chunk_embedding.chunk_embedding_id IS NULL
                    ) AS missing
                FROM document_chunks AS document_chunk
                LEFT JOIN chunk_embeddings AS chunk_embedding
                    ON chunk_embedding.chunk_id = document_chunk.chunk_id
                   AND chunk_embedding.embedding_set_id = CAST(:embeddingSetId AS uuid)
                LEFT JOIN embedding_jobs AS embedding_job
                    ON embedding_job.chunk_id = document_chunk.chunk_id
                   AND embedding_job.embedding_set_id = CAST(:embeddingSetId AS uuid)
                """.trimIndent(),
                MapSqlParameterSource("embeddingSetId", embeddingSetId.value),
            ) { row, _ ->
                ActiveEmbeddingCoverage(
                    embeddingSetId = embeddingSetId,
                    eligibleTotal = row.getLong("eligible_total"),
                    pending = row.getLong("pending"),
                    running = row.getLong("running"),
                    retrying = row.getLong("retrying"),
                    succeeded = row.getLong("succeeded"),
                    permanentlyFailed = row.getLong("permanently_failed"),
                    missing = row.getLong("missing"),
                )
            }!!
        }
}
