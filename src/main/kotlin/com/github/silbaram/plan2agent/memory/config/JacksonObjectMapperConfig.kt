package com.github.silbaram.plan2agent.memory.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonObjectMapperConfig {
    @Bean
    fun objectMapper(): ObjectMapper =
        jacksonObjectMapper().registerModule(JavaTimeModule())
}
