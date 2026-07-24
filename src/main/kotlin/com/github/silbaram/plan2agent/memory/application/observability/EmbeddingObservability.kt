package com.github.silbaram.plan2agent.memory.application.observability

import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingJobStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderException
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderContractViolationException
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps embedding operational metrics deliberately bounded. In particular, request text, IDs,
 * paths and provider error text must never become metric tags.
 */
interface EmbeddingObservability {
    fun recordProviderInitialization(outcome: EmbeddingProviderInitializationOutcome)

    fun recordJobOutcome(outcome: EmbeddingJobOutcome)

    fun <T> recordInference(operation: EmbeddingInferenceOperation, block: () -> T): T

    fun refreshJobStatusCounts()

    companion object {
        val noop: EmbeddingObservability = object : EmbeddingObservability {
            override fun recordProviderInitialization(outcome: EmbeddingProviderInitializationOutcome) = Unit

            override fun recordJobOutcome(outcome: EmbeddingJobOutcome) = Unit

            override fun <T> recordInference(operation: EmbeddingInferenceOperation, block: () -> T): T = block()

            override fun refreshJobStatusCounts() = Unit
        }
    }
}

enum class EmbeddingProviderInitializationOutcome {
    READY,
    UNAVAILABLE,
    NOT_CONFIGURED,
}

enum class EmbeddingJobOutcome {
    SUCCEEDED,
    RETRYING,
    PERMANENTLY_FAILED,
}

enum class EmbeddingInferenceOperation {
    QUERY,
    DOCUMENT,
}

@Component
class MicrometerEmbeddingObservability(
    private val meterRegistry: MeterRegistry,
    private val embeddingPortProvider: ObjectProvider<EmbeddingPort>,
    private val embeddingJobStoreProvider: ObjectProvider<EmbeddingJobStorePort>,
) : EmbeddingObservability, ApplicationListener<ApplicationReadyEvent> {
    private val jobStatusCounts = EmbeddingJobMetricStatus.entries.associateWith { AtomicLong() }

    init {
        EmbeddingProviderState.entries.forEach { state ->
            Gauge.builder(METRIC_PROVIDER_STATE, this) {
                if (providerState() == state) 1.0 else 0.0
            }
                .tag(TAG_STATE, state.metricValue())
                .register(meterRegistry)
        }
        jobStatusCounts.forEach { (status, count) ->
            Gauge.builder(METRIC_JOB_STATUS, count) { it.get().toDouble() }
                .tag(TAG_STATUS, status.metricValue())
                .register(meterRegistry)
        }
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        if (providerState() == EmbeddingProviderState.NOT_CONFIGURED) {
            recordProviderInitialization(EmbeddingProviderInitializationOutcome.NOT_CONFIGURED)
        }
        refreshJobStatusCounts()
    }

    override fun recordProviderInitialization(outcome: EmbeddingProviderInitializationOutcome) {
        meterRegistry.counter(
            METRIC_PROVIDER_INITIALIZATION,
            TAG_OUTCOME,
            outcome.metricValue(),
        ).increment()
    }

    override fun recordJobOutcome(outcome: EmbeddingJobOutcome) {
        meterRegistry.counter(
            METRIC_JOB_OUTCOME,
            TAG_OUTCOME,
            outcome.metricValue(),
        ).increment()
    }

    override fun <T> recordInference(operation: EmbeddingInferenceOperation, block: () -> T): T {
        val sample = Timer.start(meterRegistry)
        var outcome = InferenceOutcome.SUCCEEDED
        try {
            return block()
        } catch (failure: Throwable) {
            outcome = failure.inferenceOutcome()
            throw failure
        } finally {
            sample.stop(
                Timer.builder(METRIC_INFERENCE)
                    .tag(TAG_OPERATION, operation.metricValue())
                    .tag(TAG_OUTCOME, outcome.metricValue())
                    .register(meterRegistry),
            )
        }
    }

    @Scheduled(fixedDelayString = "\${p2a.memory.embedding.metrics-refresh-delay:30s}")
    override fun refreshJobStatusCounts() {
        val counts = try {
            embeddingJobStoreProvider.ifAvailable?.countByStatus().orEmpty()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) {
                throw failure
            }
            logger.atWarn()
                .addKeyValue("event", "embedding_job_metrics_refresh_failed")
                .log("Embedding job metric refresh failed")
            return
        }
        jobStatusCounts.forEach { (status, count) ->
            count.set(counts[status.toDomainStatus()] ?: 0L)
        }
    }

    private fun providerState(): EmbeddingProviderState =
        embeddingPortProvider.ifAvailable?.providerState ?: EmbeddingProviderState.NOT_CONFIGURED

    private companion object {
        private val logger = LoggerFactory.getLogger(MicrometerEmbeddingObservability::class.java)

        const val METRIC_PROVIDER_STATE = "p2a.embedding.provider.state"
        const val METRIC_PROVIDER_INITIALIZATION = "p2a.embedding.provider.initialization"
        const val METRIC_JOB_STATUS = "p2a.embedding.jobs"
        const val METRIC_JOB_OUTCOME = "p2a.embedding.jobs.outcomes"
        const val METRIC_INFERENCE = "p2a.embedding.inference"
        const val TAG_STATE = "state"
        const val TAG_STATUS = "status"
        const val TAG_OUTCOME = "outcome"
        const val TAG_OPERATION = "operation"
    }
}

private enum class EmbeddingJobMetricStatus {
    PENDING,
    RUNNING,
    RETRYING,
    SUCCEEDED,
    PERMANENTLY_FAILED,
}

private enum class InferenceOutcome {
    SUCCEEDED,
    NOT_CONFIGURED,
    UNAVAILABLE,
    CONTRACT_INVALID,
}

private fun EmbeddingProviderState.metricValue(): String = name.lowercase()

private fun EmbeddingProviderInitializationOutcome.metricValue(): String = name.lowercase()

private fun EmbeddingJobOutcome.metricValue(): String = name.lowercase()

private fun EmbeddingInferenceOperation.metricValue(): String = name.lowercase()

private fun EmbeddingJobMetricStatus.metricValue(): String = name.lowercase()

private fun InferenceOutcome.metricValue(): String = name.lowercase()

private fun EmbeddingJobMetricStatus.toDomainStatus() =
    com.github.silbaram.plan2agent.memory.domain.EmbeddingJobStatus.valueOf(name)

private fun Throwable.inferenceOutcome(): InferenceOutcome =
    when (this) {
        is ProviderNotConfiguredException -> InferenceOutcome.NOT_CONFIGURED
        is ProviderContractViolationException -> InferenceOutcome.CONTRACT_INVALID
        is EmbeddingProviderException -> InferenceOutcome.UNAVAILABLE
        else -> InferenceOutcome.UNAVAILABLE
    }
