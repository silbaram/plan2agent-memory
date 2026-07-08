package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.KeywordSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.KeywordSearchMatch
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch

interface ArtifactQueryPort {
    fun findArtifacts(query: FindArtifactsQuery): PagedResult<ArtifactSummary>
}

interface KeywordSearchPort {
    fun search(query: KeywordSearchQuery): PagedResult<KeywordSearchMatch>
}

interface VectorSearchPort {
    fun search(query: VectorSearchQuery): PagedResult<VectorSearchMatch>
}

interface ContentHashDeduplicationPort {
    fun existsByContentHash(contentHash: ContentHash): Boolean
    fun findArtifactsByContentHash(contentHash: ContentHash): List<ArtifactSummary>
}

interface SourceReferenceResolutionPort {
    fun resolve(canonicalServerId: CanonicalServerId, rawReference: String): SourceReference?
}
