package com.github.silbaram.plan2agent.memory.application.worker

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingBackfillResult
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingBackfillStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingCoverage
import com.github.silbaram.plan2agent.memory.application.port.out.StructurallyValidPersistedActiveEmbeddingSetResolver
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/**
 * Reconciles existing chunks against the one persisted V2 target. It never depends on provider
 * readiness: jobs stay pending until [EmbeddingJobWorker] can claim them without consuming an
 * attempt in degraded mode.
 */
@Component
@ConditionalOnProperty(prefix = "p2a.memory.embedding.worker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ActiveEmbeddingBackfillReconciler(
    private val activeTargetResolver: StructurallyValidPersistedActiveEmbeddingSetResolver,
    private val backfillStore: ActiveEmbeddingBackfillStorePort,
    private val properties: EmbeddingWorkerProperties,
    private val clock: Clock,
) {
    private val cursor = AtomicReference<DocumentChunkId?>(null)

    @EventListener(ApplicationReadyEvent::class)
    fun reconcileAfterStartup() {
        reconcileNow()
    }

    @Scheduled(fixedDelayString = "\${p2a.memory.embedding.worker.backfill-poll-delay:1m}")
    fun reconcilePeriodically() {
        reconcileNow()
    }

    fun reconcileNow(): ActiveEmbeddingBackfillResult? {
        val embeddingSetId = activeTargetResolver.findStructurallyValidActiveV2EmbeddingSetId() ?: return null
        val result = backfillStore.reconcile(
            embeddingSetId = embeddingSetId,
            batchSize = properties.backfillBatchSize,
            afterChunkId = cursor.get(),
            enqueuedAt = clock.instant(),
        )
        cursor.set(result.nextChunkId)
        return result
    }

    fun currentCoverage(): ActiveEmbeddingCoverage? =
        activeTargetResolver.findStructurallyValidActiveV2EmbeddingSetId()
            ?.let(backfillStore::coverage)
}
