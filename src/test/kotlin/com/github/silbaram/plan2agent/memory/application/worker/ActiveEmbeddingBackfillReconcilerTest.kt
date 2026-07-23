package com.github.silbaram.plan2agent.memory.application.worker

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingBackfillResult
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingBackfillStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingCoverage
import com.github.silbaram.plan2agent.memory.application.port.out.StructurallyValidPersistedActiveEmbeddingSetResolver
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ActiveEmbeddingBackfillReconcilerTest {
    @Test
    fun `startup and periodic triggers advance the keyset cursor with the configured batch size`() {
        val activeSet = EmbeddingSetId("341ee30d-345a-488f-afb7-bf46cf0a2d61")
        val store = RecordingBackfillStore(
            results = ArrayDeque(
                listOf(
                    ActiveEmbeddingBackfillResult(
                        scannedChunks = 500,
                        repairedTypedMirrors = 1,
                        enqueuedJobs = 499,
                        nextChunkId = DocumentChunkId("00000000-0000-0000-0000-000000000100"),
                    ),
                    ActiveEmbeddingBackfillResult(
                        scannedChunks = 1,
                        repairedTypedMirrors = 0,
                        enqueuedJobs = 1,
                        nextChunkId = DocumentChunkId("00000000-0000-0000-0000-000000000101"),
                    ),
                ),
            ),
        )
        val reconciler = ActiveEmbeddingBackfillReconciler(
            activeTargetResolver = StructurallyValidPersistedActiveEmbeddingSetResolver { activeSet },
            backfillStore = store,
            properties = EmbeddingWorkerProperties(backfillBatchSize = 500),
            clock = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC),
        )

        reconciler.reconcileAfterStartup()
        reconciler.reconcilePeriodically()

        assertThat(store.calls).hasSize(2)
        assertThat(store.calls).extracting(
            { it.embeddingSetId },
            { it.batchSize },
            { it.afterChunkId },
            { it.enqueuedAt },
        ).containsExactly(
            org.assertj.core.groups.Tuple.tuple(activeSet, 500, null, Instant.parse("2026-07-24T00:00:00Z")),
            org.assertj.core.groups.Tuple.tuple(
                activeSet,
                500,
                DocumentChunkId("00000000-0000-0000-0000-000000000100"),
                Instant.parse("2026-07-24T00:00:00Z"),
            ),
        )
    }

    @Test
    fun `an absent or invalid active pointer leaves reconciliation and coverage untouched`() {
        val store = RecordingBackfillStore(ArrayDeque())
        val reconciler = ActiveEmbeddingBackfillReconciler(
            activeTargetResolver = StructurallyValidPersistedActiveEmbeddingSetResolver { null },
            backfillStore = store,
            properties = EmbeddingWorkerProperties(),
            clock = Clock.systemUTC(),
        )

        assertThat(reconciler.reconcileNow()).isNull()
        assertThat(reconciler.currentCoverage()).isNull()
        assertThat(store.calls).isEmpty()
        assertThat(store.coverageCalls).isEmpty()
    }

    private class RecordingBackfillStore(
        private val results: ArrayDeque<ActiveEmbeddingBackfillResult>,
    ) : ActiveEmbeddingBackfillStorePort {
        val calls = mutableListOf<ReconcileCall>()
        val coverageCalls = mutableListOf<EmbeddingSetId>()

        override fun reconcile(
            embeddingSetId: EmbeddingSetId,
            batchSize: Int,
            afterChunkId: DocumentChunkId?,
            enqueuedAt: Instant,
        ): ActiveEmbeddingBackfillResult {
            calls += ReconcileCall(embeddingSetId, batchSize, afterChunkId, enqueuedAt)
            return results.removeFirst()
        }

        override fun coverage(embeddingSetId: EmbeddingSetId): ActiveEmbeddingCoverage {
            coverageCalls += embeddingSetId
            return ActiveEmbeddingCoverage(
                embeddingSetId = embeddingSetId,
                eligibleTotal = 0,
                pending = 0,
                running = 0,
                retrying = 0,
                succeeded = 0,
                permanentlyFailed = 0,
                missing = 0,
            )
        }
    }

    private data class ReconcileCall(
        val embeddingSetId: EmbeddingSetId,
        val batchSize: Int,
        val afterChunkId: DocumentChunkId?,
        val enqueuedAt: Instant,
    )
}
