package com.github.silbaram.plan2agent.memory.application.worker

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.observability.EmbeddingInferenceOperation
import com.github.silbaram.plan2agent.memory.application.observability.EmbeddingJobOutcome
import com.github.silbaram.plan2agent.memory.application.observability.EmbeddingObservability
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolutionException
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolver
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentChunkStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderException
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingJobStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderContractViolationException
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJob
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobErrorCode
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobFailure
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.time.Clock
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
    private val clock: Clock,
    private val observability: EmbeddingObservability = EmbeddingObservability.noop,
) {
    private val owner = "embedding-worker-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${p2a.memory.embedding.worker.poll-delay:1s}")
    fun poll() {
        try {
            embeddingJobStore.recoverExpiredLeases()
            val activeTarget = currentActiveTarget() ?: return
            val claimed = embeddingJobStore.claimDueForEmbeddingSet(
                embeddingSetId = requireNotNull(activeTarget.embeddingSetId),
                batchSize = properties.claimBatchSize,
                owner = owner,
                leaseUntil = clock.instant().plus(properties.leaseDuration),
            )

            claimed
                .map { job -> workerExecutor.submit { process(job, activeTarget) } }
                .forEach { future -> future.get() }
        } finally {
            observability.refreshJobStatusCounts()
        }
    }

    private fun process(job: EmbeddingJob, expectedTarget: ActiveEmbeddingTarget) {
        if (!canInferFor(job, expectedTarget)) {
            releaseClaim(job)
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

        val result = try {
            observability.recordInference(EmbeddingInferenceOperation.DOCUMENT) {
                embeddingPort.embedDocuments(listOf(chunk.content)).singleOrNull()
                    ?: throw ProviderContractViolationException()
            }
        } catch (_: ProviderContractViolationException) {
            releaseClaim(job)
            return
        } catch (failure: EmbeddingProviderException) {
            handleProviderFailure(job, failure)
            return
        }
        if (result.target != expectedTarget) {
            releaseClaim(job)
            return
        }
        try {
            validateEmbedding(result.embedding, expectedTarget)
        } catch (_: ProviderContractViolationException) {
            releaseClaim(job)
            return
        }

        try {
            completionService.saveEmbeddingAndMarkSucceeded(
                job = job,
                chunk = chunk,
                embedding = result.embedding,
                owner = owner,
            )
            observability.recordJobOutcome(EmbeddingJobOutcome.SUCCEEDED)
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

    private fun handleProviderFailure(job: EmbeddingJob, failure: EmbeddingProviderException) {
        if (failure is ProviderNotConfiguredException || embeddingPort.providerState != EmbeddingProviderState.READY) {
            releaseClaim(job)
            return
        }

        if (!failure.retryable) {
            val transitioned = embeddingJobStore.markPermanentlyFailed(
                jobId = job.id,
                owner = owner,
                leaseGeneration = job.leaseGeneration,
                failure = providerFailure(EmbeddingJobErrorCode.PROVIDER_UNAVAILABLE),
            )
            if (transitioned != null) {
                observability.recordJobOutcome(EmbeddingJobOutcome.PERMANENTLY_FAILED)
            }
            logProviderFailure(EmbeddingJobOutcome.PERMANENTLY_FAILED)
            return
        }

        if (job.attemptCount >= properties.maxAttempts) {
            val transitioned = embeddingJobStore.markPermanentlyFailed(
                jobId = job.id,
                owner = owner,
                leaseGeneration = job.leaseGeneration,
                failure = EmbeddingJobFailure.fromUntrustedMessage(EmbeddingJobErrorCode.MAX_ATTEMPTS_EXHAUSTED, ""),
            )
            if (transitioned != null) {
                observability.recordJobOutcome(EmbeddingJobOutcome.PERMANENTLY_FAILED)
            }
            logProviderFailure(EmbeddingJobOutcome.PERMANENTLY_FAILED)
            return
        }

        val transitioned = embeddingJobStore.markRetrying(
            jobId = job.id,
            owner = owner,
            leaseGeneration = job.leaseGeneration,
            failure = providerFailure(EmbeddingJobErrorCode.PROVIDER_UNAVAILABLE),
            nextAttemptAt = clock.instant().plus(retryDelay(job.attemptCount)),
        )
        if (transitioned != null) {
            observability.recordJobOutcome(EmbeddingJobOutcome.RETRYING)
        }
        logProviderFailure(EmbeddingJobOutcome.RETRYING)
    }

    private fun releaseClaim(job: EmbeddingJob) {
        embeddingJobStore.releaseClaimToPending(job.id, owner, job.leaseGeneration)
    }

    private fun providerFailure(code: EmbeddingJobErrorCode): EmbeddingJobFailure =
        EmbeddingJobFailure.fromUntrustedMessage(code, "")

    private fun logProviderFailure(outcome: EmbeddingJobOutcome) {
        logger.atWarn()
            .addKeyValue("event", "embedding_job_provider_failure")
            .addKeyValue("outcome", outcome.name.lowercase())
            .log("Embedding job provider failure")
    }

    private fun retryDelay(attemptCount: Int): java.time.Duration {
        var delay = properties.initialRetryDelay
        var remainingDoublings = attemptCount - 1
        val halfOfMaximum = properties.maxRetryDelay.dividedBy(2)

        while (remainingDoublings > 0 && delay < properties.maxRetryDelay) {
            delay = if (delay > halfOfMaximum) {
                properties.maxRetryDelay
            } else {
                delay.multipliedBy(2)
            }
            remainingDoublings -= 1
        }

        return delay
    }

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
        val transitioned = embeddingJobStore.markPermanentlyFailed(
            jobId = job.id,
            owner = owner,
            leaseGeneration = job.leaseGeneration,
            failure = EmbeddingJobFailure.fromUntrustedMessage(EmbeddingJobErrorCode.CONTENT_INVALID, ""),
        )
        if (transitioned != null) {
            observability.recordJobOutcome(EmbeddingJobOutcome.PERMANENTLY_FAILED)
        }
    }

    private companion object {
        const val NORMALIZATION_TOLERANCE = 0.001
        val logger = LoggerFactory.getLogger(EmbeddingJobWorker::class.java)
    }
}
