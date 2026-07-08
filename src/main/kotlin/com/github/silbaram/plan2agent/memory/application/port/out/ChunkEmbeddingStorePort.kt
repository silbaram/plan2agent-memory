package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.ChunkEmbedding
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId

interface ChunkEmbeddingStorePort {
    fun saveAll(chunkEmbeddings: List<ChunkEmbedding>): List<ChunkEmbedding>

    fun findByChunkId(chunkId: DocumentChunkId): List<ChunkEmbedding>
}
