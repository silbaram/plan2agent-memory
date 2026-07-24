package com.github.silbaram.plan2agent.memory.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Keeps scheduled work enabled in normal server operation while allowing integration tests to
 * close their Testcontainers database without a still-live scheduler making a final query.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "p2a.memory.scheduling",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class MemorySchedulingConfiguration
