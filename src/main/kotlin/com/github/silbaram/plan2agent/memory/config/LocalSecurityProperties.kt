package com.github.silbaram.plan2agent.memory.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "p2a.security")
data class LocalSecurityProperties(
    val token: String? = null,
    val headerName: String = "X-P2A-Local-Token",
    val protectedPaths: List<String> = listOf("/api/**"),
    val excludedPaths: List<String> = listOf("/api/health", "/actuator/health"),
) {
    init {
        require(headerName.isNotBlank()) { "p2a.security.header-name must not be blank" }
        require(protectedPaths.isNotEmpty()) { "p2a.security.protected-paths must not be empty" }
        require(protectedPaths.all { it.isNotBlank() }) { "p2a.security.protected-paths must not contain blank paths" }
        require(excludedPaths.all { it.isNotBlank() }) { "p2a.security.excluded-paths must not contain blank paths" }
    }
}
