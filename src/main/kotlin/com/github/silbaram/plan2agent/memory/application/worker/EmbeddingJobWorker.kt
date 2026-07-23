package com.github.silbaram.plan2agent.memory.application.worker

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolutionException
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolver
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentChunkStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingJobStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderContractViolationException
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJob
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobErrorCode
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobFailure
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Polls the durable job table. Scheduling only triggers work; the database remains the queue's
 * source of truth across process restarts.
 */
@Component
@ConditionalOnProperty(prefix = "p2a.memory.embedding.worker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class EmbeddingJobWorker(
    private val embeddingPort: EmbeddingPort,
    private val activeEmbeddingProfileResolver: ActiveEmbeddingProfileResolver,
    private val documentChunkStore: DocumentChunkStorePort,
    private val embeddingJobStore: EmbeddingJobStorePort,
    private val completionService: EmbeddingJobCompletionService,
    private val properties: EmbeddingWorkerProperties,
    private val workerExecutor: ExecutorService,
) {
    private val owner = "embedding-worker-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${p2a.memory.embedding.worker.poll-delay:1s}")
    fun poll() {
        embeddingJobStore.recoverExpiredLeases()
        val activeTarget = currentActiveTarget() ?: return
        val claimed = embeddingJobStore.claimDueForEmbeddingSet(
            embeddingSetId = requireNotNull(activeTarget.embeddingSetId),
            batchSize = properties.claimBatchSize,
            owner = owner,
            leaseUntil = Instant.now().plus(properties.leaseDuration),
        )

        claimed
            .map { job -> workerExecutor.submit { process(job, activeTarget) } }
            .forEach { future -> future.get() }
    }

    private fun process(job: EmbeddingJob, expectedTarget: ActiveEmbeddingTarget) {
        if (!canInferFor(job, expectedTarget)) {
            return
        }

        val chunk = try {
            documentChunkStore.findById(job.chunkId)
        } catch (_: IllegalArgumentException) {
            markInvalidChunk(job)
            return
        }
        if (chunk == null) {
            return
        }

        val result = embeddingPort.embedDocuments(listOf(chunk.content)).singleOrNull()
            ?: throw ProviderContractViolationException()
        if (result.target != expectedTarget) {
            throw ProviderContractViolationException()
        }
        validateEmbedding(result.embedding, expectedTarget)

        try {
            completionService.saveEmbeddingAndMarkSucceeded(
                job = job,
                chunk = chunk,
                embedding = result.embedding,
                owner = owner,
            )
        } catch (_: StaleEmbeddingJobCompletionException) {
            // Another generation owns this job. The completion service has already rolled back its vector writes.
        } catch (failure: DataIntegrityViolationException) {
            if (documentChunkStore.findById(job.chunkId) != null) {
                throw failure
            }
        }
    }

    private fun currentActiveTarget(): ActiveEmbeddingTarget? {
        if (embeddingPort.providerState != EmbeddingProviderState.READY) {
            return null
        }
        val target = embeddingPort.activeEmbeddingTarget
        val targetId = target.embeddingSetId ?: return null
        if (target.profile != V2EmbeddingProfile.fixed) {
            return null
        }
        val persistedTargetId = try {
            activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId()
        } catch (_: ActiveEmbeddingProfileResolutionException) {
            return null
        }
        return target.takeIf { targetId == persistedTargetId }
    }

    private fun canInferFor(job: EmbeddingJob, expectedTarget: ActiveEmbeddingTarget): Boolean =
        embeddingPort.providerState == EmbeddingProviderState.READY &&
            job.embeddingSetId == expectedTarget.embeddingSetId &&
            embeddingPort.activeEmbeddingTarget == expectedTarget

    private fun validateEmbedding(embedding: Embedding, target: ActiveEmbeddingTarget) {
        val values = embedding.values
        if (values.size != target.profile.dimension || values.any { !it.isFinite() }) {
            throw ProviderContractViolationException()
        }
        val norm = sqrt(values.sumOf { value -> value.toDouble() * value.toDouble() })
        if (!norm.isFinite() || abs(norm - 1.0) > NORMALIZATION_TOLERANCE) {
            throw ProviderContractViolationException()
        }
    }

    private fun markInvalidChunk(job: EmbeddingJob) {
        embeddingJobStore.markPermanentlyFailed(
            jobId = job.id,
            owner = owner,
            leaseGeneration = job.leaseGeneration,
            failure = EmbeddingJobFailure.fromUntrustedMessage(
                EmbeddingJobErrorCode.CONTENT_INVALID,
                "Document chunk is invalid",
            ),
        )
    }

    private companion object {
        const val NORMALIZATION_TOLERANCE = 0.001
    }
}
