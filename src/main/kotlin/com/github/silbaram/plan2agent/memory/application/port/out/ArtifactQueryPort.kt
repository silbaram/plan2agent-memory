package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary

interface ArtifactQueryPort {
    fun findArtifacts(query: FindArtifactsQuery): PagedResult<ArtifactSummary>
}
