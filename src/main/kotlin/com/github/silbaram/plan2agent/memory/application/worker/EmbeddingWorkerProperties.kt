package com.github.silbaram.plan2agent.memory.application.worker

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "p2a.memory.embedding.worker", ignoreUnknownFields = false)
data class EmbeddingWorkerProperties(
    val enabled: Boolean = true,
    val pollDelay: Duration = Duration.ofSeconds(1),
    val backfillPollDelay: Duration = Duration.ofMinutes(1),
    val backfillBatchSize: Int = 500,
    val claimBatchSize: Int = 16,
    val concurrency: Int = 1,
    val leaseDuration: Duration = Duration.ofMinutes(2),
    val maxAttempts: Int = 5,
    val initialRetryDelay: Duration = Duration.ofSeconds(5),
    val maxRetryDelay: Duration = Duration.ofMinutes(5),
) {
    init {
        require(!pollDelay.isNegative && !pollDelay.isZero) { "Embedding worker pollDelay must be positive" }
        require(!backfillPollDelay.isNegative && !backfillPollDelay.isZero) {
            "Embedding worker backfillPollDelay must be positive"
        }
        require(backfillBatchSize > 0) { "Embedding worker backfillBatchSize must be positive" }
        require(claimBatchSize > 0) { "Embedding worker claimBatchSize must be positive" }
        require(concurrency > 0) { "Embedding worker concurrency must be positive" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "Embedding worker leaseDuration must be positive" }
        require(maxAttempts > 0) { "Embedding worker maxAttempts must be positive" }
        require(!initialRetryDelay.isNegative && !initialRetryDelay.isZero) {
            "Embedding worker initialRetryDelay must be positive"
        }
        require(!maxRetryDelay.isNegative && !maxRetryDelay.isZero) {
            "Embedding worker maxRetryDelay must be positive"
        }
        require(initialRetryDelay <= maxRetryDelay) {
            "Embedding worker initialRetryDelay must not exceed maxRetryDelay"
        }
    }
}
