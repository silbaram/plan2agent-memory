package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSet
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId

interface EmbeddingSetStorePort {
    fun resolveOrCreate(embeddingSet: EmbeddingSet): EmbeddingSet

    fun findById(id: EmbeddingSetId): EmbeddingSet?

    fun findByUniqueKey(
        embeddingModel: String,
        embeddingDimension: Int,
        embeddingVersion: String,
        distanceMetric: DistanceMetric,
    ): EmbeddingSet?
}
