package com.github.silbaram.plan2agent.memory.config

import com.github.silbaram.plan2agent.memory.application.worker.EmbeddingWorkerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

@Configuration(proxyBeanMethods = false)
class EmbeddingWorkerConfiguration {
    @Bean
    fun embeddingWorkerClock(): Clock = Clock.systemUTC()

    @Bean(destroyMethod = "shutdown")
    fun embeddingWorkerExecutor(properties: EmbeddingWorkerProperties): ExecutorService =
        Executors.newFixedThreadPool(
            properties.concurrency,
            ThreadFactory { runnable ->
                Thread(runnable, "p2a-embedding-worker").apply { isDaemon = true }
            },
        )
}
