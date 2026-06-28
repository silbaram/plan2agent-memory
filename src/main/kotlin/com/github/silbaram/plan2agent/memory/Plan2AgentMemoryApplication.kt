package com.github.silbaram.plan2agent.memory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Plan2AgentMemoryApplication

fun main(args: Array<String>) {
    runApplication<Plan2AgentMemoryApplication>(*args)
}

