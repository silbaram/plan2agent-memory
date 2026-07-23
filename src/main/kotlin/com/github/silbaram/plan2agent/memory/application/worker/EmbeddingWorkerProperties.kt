package com.github.silbaram.plan2agent.memory.application.worker

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "p2a.memory.embedding.worker", ignoreUnknownFields = false)
data class EmbeddingWorkerProperties(
    val enabled: Boolean = true,
    val pollDelay: Duration = Duration.ofSeconds(1),
    val claimBatchSize: Int = 16,
    val concurrency: Int = 1,
    val leaseDuration: Duration = Duration.ofMinutes(2),
) {
    init {
        require(!pollDelay.isNegative && !pollDelay.isZero) { "Embedding worker pollDelay must be positive" }
        require(claimBatchSize > 0) { "Embedding worker claimBatchSize must be positive" }
        require(concurrency > 0) { "Embedding worker concurrency must be positive" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "Embedding worker leaseDuration must be positive" }
    }
}
