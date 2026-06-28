package com.github.silbaram.plan2agent.memory.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "p2a.security")
data class LocalSecurityProperties(
    val token: String? = null,
    val headerName: String = "X-P2A-Local-Token",
)

