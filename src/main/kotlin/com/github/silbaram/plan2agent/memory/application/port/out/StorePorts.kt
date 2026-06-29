package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.ChunkEmbedding
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSet
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.Iteration
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.Project
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.RunRecord
import com.github.silbaram.plan2agent.memory.domain.Task
import com.github.silbaram.plan2agent.memory.domain.TaskGraph
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId

interface ProjectStorePort {
    fun save(project: Project): Project
    fun findById(id: ProjectId): Project?
}

interface IterationStorePort {
    fun save(iteration: Iteration): Iteration
    fun findById(id: IterationId): Iteration?
    fun findByProjectId(projectId: ProjectId): List<Iteration>
}

interface DocumentSnapshotStorePort {
    fun save(documentSnapshot: DocumentSnapshot): DocumentSnapshot
    fun findById(id: DocumentId): DocumentSnapshot?
    fun findByIterationId(iterationId: IterationId): List<DocumentSnapshot>
}

interface TaskGraphStorePort {
    fun save(taskGraph: TaskGraph): TaskGraph
    fun findById(id: TaskGraphId): TaskGraph?
    fun findByIterationId(iterationId: IterationId): List<TaskGraph>
}

interface TaskStorePort {
    fun saveAll(tasks: List<Task>): List<Task>
    fun findById(id: TaskId): Task?
    fun findByGraphId(graphId: TaskGraphId): List<Task>
}

interface RunRecordStorePort {
    fun save(runRecord: RunRecord): RunRecord
    fun findById(id: RunId): RunRecord?
    fun findByTaskId(taskId: TaskId): List<RunRecord>
}

interface DocumentChunkStorePort {
    fun saveAll(chunks: List<DocumentChunk>): List<DocumentChunk>
    fun findByDocumentId(documentId: DocumentId): List<DocumentChunk>
}

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

interface ChunkEmbeddingStorePort {
    fun saveAll(chunkEmbeddings: List<ChunkEmbedding>): List<ChunkEmbedding>

    fun findByChunkId(chunkId: DocumentChunkId): List<ChunkEmbedding>
}
