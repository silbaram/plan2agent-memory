package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
import com.github.silbaram.plan2agent.memory.domain.ContentHash

interface ContentHashDeduplicationPort {
    fun existsByContentHash(contentHash: ContentHash): Boolean
    fun findArtifactsByContentHash(contentHash: ContentHash): List<ArtifactSummary>
}
