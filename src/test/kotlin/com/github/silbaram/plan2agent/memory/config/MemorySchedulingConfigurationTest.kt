package com.github.silbaram.plan2agent.memory.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor

class MemorySchedulingConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(MemorySchedulingConfiguration::class.java)

    @Test
    fun `scheduling is enabled by default for the running server`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor::class.java)
        }
    }

    @Test
    fun `integration tests can disable scheduling before their database container stops`() {
        contextRunner
            .withPropertyValues("p2a.memory.scheduling.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor::class.java)
            }
    }
}
