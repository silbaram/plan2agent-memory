package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentId

interface DocumentChunkStorePort {
    fun saveAll(chunks: List<DocumentChunk>): List<DocumentChunk>
    fun findByDocumentId(documentId: DocumentId): List<DocumentChunk>
}
