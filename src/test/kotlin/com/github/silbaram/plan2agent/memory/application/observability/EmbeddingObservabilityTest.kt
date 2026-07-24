package com.github.silbaram.plan2agent.memory.application.observability

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingJobStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingResult
import com.github.silbaram.plan2agent.memory.application.port.out.EnqueueEmbeddingJobCommand
import com.github.silbaram.plan2agent.memory.application.port.out.FindEmbeddingJobsQuery
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderUnavailableException
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJob
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobFailure
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobStatus
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.time.Instant

class EmbeddingObservabilityTest {
    @Test
    fun `reports fixed-cardinality provider queue outcome and inference metrics`() {
        val registry = SimpleMeterRegistry()
        val provider = StatefulEmbeddingPort()
        val jobStore = CountingEmbeddingJobStore(
            mapOf(
                EmbeddingJobStatus.PENDING to 4L,
                EmbeddingJobStatus.RETRYING to 2L,
                EmbeddingJobStatus.PERMANENTLY_FAILED to 1L,
            ),
        )
        val observability = MicrometerEmbeddingObservability(
            registry,
            providerFor(provider),
            providerFor(jobStore),
        )

        observability.recordProviderInitialization(EmbeddingProviderInitializationOutcome.READY)
        observability.recordJobOutcome(EmbeddingJobOutcome.SUCCEEDED)
        observability.recordJobOutcome(EmbeddingJobOutcome.RETRYING)
        observability.recordJobOutcome(EmbeddingJobOutcome.PERMANENTLY_FAILED)
        observability.refreshJobStatusCounts()
        observability.recordInference(EmbeddingInferenceOperation.QUERY) { "embedded" }
        assertThatThrownBy {
            observability.recordInference(EmbeddingInferenceOperation.DOCUMENT) {
                throw ProviderUnavailableException("provider body with token=must-not-be-a-tag")
            }
        }.isInstanceOf(ProviderUnavailableException::class.java)

        assertThat(gauge(registry, "p2a.embedding.provider.state", "state", "ready")).isEqualTo(1.0)
        provider.providerState = EmbeddingProviderState.UNAVAILABLE
        assertThat(gauge(registry, "p2a.embedding.provider.state", "state", "ready")).isZero()
        assertThat(gauge(registry, "p2a.embedding.provider.state", "state", "unavailable")).isEqualTo(1.0)
        assertThat(gauge(registry, "p2a.embedding.jobs", "status", "pending")).isEqualTo(4.0)
        assertThat(gauge(registry, "p2a.embedding.jobs", "status", "retrying")).isEqualTo(2.0)
        assertThat(gauge(registry, "p2a.embedding.jobs", "status", "permanently_failed")).isEqualTo(1.0)
        assertThat(counter(registry, "p2a.embedding.provider.initialization", "outcome", "ready")).isEqualTo(1.0)
        assertThat(counter(registry, "p2a.embedding.jobs.outcomes", "outcome", "succeeded")).isEqualTo(1.0)
        assertThat(counter(registry, "p2a.embedding.jobs.outcomes", "outcome", "retrying")).isEqualTo(1.0)
        assertThat(counter(registry, "p2a.embedding.jobs.outcomes", "outcome", "permanently_failed")).isEqualTo(1.0)
        assertThat(registry.find("p2a.embedding.inference").tag("operation", "query").tag("outcome", "succeeded").timer()?.count())
            .isEqualTo(1)
        assertThat(registry.find("p2a.embedding.inference").tag("operation", "document").tag("outcome", "unavailable").timer()?.count())
            .isEqualTo(1)

        registry.meters
            .filter { it.id.name.startsWith("p2a.embedding.") }
            .flatMap { it.id.tags }
            .forEach { tag ->
                assertThat(tag.key).isIn("state", "status", "outcome", "operation")
                assertThat(tag.value).doesNotContain("must-not-be-a-tag")
            }
    }

    private fun gauge(registry: SimpleMeterRegistry, name: String, tag: String, value: String): Double =
        requireNotNull(registry.find(name).tag(tag, value).gauge()).value()

    private fun counter(registry: SimpleMeterRegistry, name: String, tag: String, value: String): Double =
        requireNotNull(registry.find(name).tag(tag, value).counter()).count()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> providerFor(bean: T): org.springframework.beans.factory.ObjectProvider<T> =
        StaticListableBeanFactory()
            .apply { addBean("test", bean) }
            .getBeanProvider(bean.javaClass) as org.springframework.beans.factory.ObjectProvider<T>

    private class StatefulEmbeddingPort(
        override var providerState: EmbeddingProviderState = EmbeddingProviderState.READY,
    ) : EmbeddingPort {
        override val activeEmbeddingTarget: ActiveEmbeddingTarget = ActiveEmbeddingTarget(V2EmbeddingProfile.fixed)

        override fun embedDocuments(documents: List<String>): List<EmbeddingResult> = error("unused")

        override fun embedQuery(query: String): EmbeddingResult = error("unused")
    }

    private class CountingEmbeddingJobStore(
        private val counts: Map<EmbeddingJobStatus, Long>,
    ) : EmbeddingJobStorePort {
        override fun enqueueMissing(command: EnqueueEmbeddingJobCommand): EmbeddingJob = error("unused")

        override fun claimDue(batchSize: Int, owner: String, leaseUntil: Instant): List<EmbeddingJob> = error("unused")

        override fun claimDueForEmbeddingSet(
            embeddingSetId: EmbeddingSetId,
            batchSize: Int,
            owner: String,
            leaseUntil: Instant,
        ): List<EmbeddingJob> = error("unused")

        override fun releaseClaimToPending(
            jobId: EmbeddingJobId,
            owner: String,
            leaseGeneration: Long,
        ): EmbeddingJob? = error("unused")

        override fun markRetrying(
            jobId: EmbeddingJobId,
            owner: String,
            leaseGeneration: Long,
            failure: EmbeddingJobFailure,
            nextAttemptAt: Instant,
        ): EmbeddingJob? = error("unused")

        override fun markSucceeded(jobId: EmbeddingJobId, owner: String, leaseGeneration: Long): EmbeddingJob? = error("unused")

        override fun markPermanentlyFailed(
            jobId: EmbeddingJobId,
            owner: String,
            leaseGeneration: Long,
            failure: EmbeddingJobFailure,
        ): EmbeddingJob? = error("unused")

        override fun recoverExpiredLeases(): List<EmbeddingJob> = error("unused")

        override fun countByStatus(): Map<EmbeddingJobStatus, Long> = counts

        override fun findById(id: EmbeddingJobId): EmbeddingJob? = error("unused")

        override fun findPage(query: FindEmbeddingJobsQuery): PagedResult<EmbeddingJob> = error("unused")

        override fun retryFailed(jobId: EmbeddingJobId): EmbeddingJob? = error("unused")
    }
}
