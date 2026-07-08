package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
import com.github.silbaram.plan2agent.memory.domain.IterationId

interface DocumentSnapshotStorePort {
    fun save(documentSnapshot: DocumentSnapshot): DocumentSnapshot
    fun findById(id: DocumentId): DocumentSnapshot?
    fun findByIterationId(iterationId: IterationId): List<DocumentSnapshot>
}
