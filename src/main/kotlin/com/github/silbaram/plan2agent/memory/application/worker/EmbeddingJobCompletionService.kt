package com.github.silbaram.plan2agent.memory.application.worker

import com.github.silbaram.plan2agent.memory.application.port.out.ChunkEmbeddingStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingJobStorePort
import com.github.silbaram.plan2agent.memory.domain.ChunkEmbedding
import com.github.silbaram.plan2agent.memory.domain.ChunkEmbeddingId
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJob
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * Commits a vector and its job completion together. The completion compare-and-set is deliberately
 * last: a stale lease generation turns into an exception, which rolls back every vector write from
 * this transaction.
 */
@Service
class EmbeddingJobCompletionService(
    private val chunkEmbeddingStore: ChunkEmbeddingStorePort,
    private val embeddingJobStore: EmbeddingJobStorePort,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun saveEmbeddingAndMarkSucceeded(
        job: EmbeddingJob,
        chunk: DocumentChunk,
        embedding: Embedding,
        owner: String,
    ) {
        transactions.executeWithoutResult {
            chunkEmbeddingStore.saveAll(
                listOf(
                    ChunkEmbedding(
                        id = deterministicChunkEmbeddingId(chunk.id.value, job.embeddingSetId.value),
                        embeddingSetId = job.embeddingSetId,
                        chunkId = chunk.id,
                        embedding = embedding,
                        createdAt = Instant.now(),
                        metadata = chunk.metadata,
                    ),
                ),
            )
            if (embeddingJobStore.markSucceeded(job.id, owner, job.leaseGeneration) == null) {
                throw StaleEmbeddingJobCompletionException()
            }
        }
    }

    private fun deterministicChunkEmbeddingId(chunkId: String, embeddingSetId: String): ChunkEmbeddingId =
        ChunkEmbeddingId(
            UUID.nameUUIDFromBytes(
                "chunk-embedding:$chunkId:$embeddingSetId".toByteArray(StandardCharsets.UTF_8),
            ).toString(),
        )
}

class StaleEmbeddingJobCompletionException : RuntimeException("Embedding job completion lease is stale")
