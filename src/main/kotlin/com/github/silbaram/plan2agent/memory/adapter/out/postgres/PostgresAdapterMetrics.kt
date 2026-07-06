package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class PostgresAdapterMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun <T> recordSearch(operation: String, block: () -> T): T =
        record("p2a.memory.search", operation, block)

    fun <T> recordWrite(operation: String, block: () -> T): T =
        record("p2a.memory.write", operation, block)

    private fun <T> record(prefix: String, operation: String, block: () -> T): T {
        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        try {
            return block()
        } catch (failure: Throwable) {
            outcome = "failure"
            throw failure
        } finally {
            sample.stop(
                Timer.builder("$prefix.duration")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(meterRegistry),
            )
            meterRegistry.counter(
                "$prefix.calls",
                "operation",
                operation,
                "outcome",
                outcome,
            ).increment()
        }
    }
}
