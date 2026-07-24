package com.github.silbaram.plan2agent.memory.application.port.`in`

import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.HybridSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.KeywordSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.RegisterIterationCommand
import com.github.silbaram.plan2agent.memory.application.usecase.RegisterProjectCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentChunksCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveArtifactGraphSnapshotCommand
import com.github.silbaram.plan2agent.memory.application.usecase.ArtifactGraphSnapshotResult
import com.github.silbaram.plan2agent.memory.application.usecase.GraphNodeSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.GraphTraceQuery
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentSnapshotCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveRunRecordCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveTaskGraphCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveTasksCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SemanticSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.application.port.out.FindEmbeddingJobsQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
import com.github.silbaram.plan2agent.memory.domain.ArtifactNode
import com.github.silbaram.plan2agent.memory.domain.ArtifactTrace
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJob
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobId
import com.github.silbaram.plan2agent.memory.domain.HybridSearchMatch
import com.github.silbaram.plan2agent.memory.domain.Iteration
import com.github.silbaram.plan2agent.memory.domain.KeywordSearchMatch
import com.github.silbaram.plan2agent.memory.domain.Project
import com.github.silbaram.plan2agent.memory.domain.RunRecord
import com.github.silbaram.plan2agent.memory.domain.Task
import com.github.silbaram.plan2agent.memory.domain.TaskGraph
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch

interface RegisterProjectUseCase {
    fun registerProject(command: RegisterProjectCommand): Project
}

interface RegisterIterationUseCase {
    fun registerIteration(command: RegisterIterationCommand): Iteration
}

interface SaveDocumentSnapshotUseCase {
    fun saveDocumentSnapshot(command: SaveDocumentSnapshotCommand): DocumentSnapshot
}

interface SaveTaskGraphUseCase {
    fun saveTaskGraph(command: SaveTaskGraphCommand): TaskGraph
}

interface SaveTasksUseCase {
    fun saveTasks(command: SaveTasksCommand): List<Task>
}

interface SaveRunRecordUseCase {
    fun saveRunRecord(command: SaveRunRecordCommand): RunRecord
}

interface SaveDocumentChunksUseCase {
    fun saveDocumentChunks(command: SaveDocumentChunksCommand): List<DocumentChunk>
}

interface FindArtifactsUseCase {
    fun findArtifacts(query: FindArtifactsQuery): PagedResult<ArtifactSummary>
}

interface KeywordSearchUseCase {
    fun keywordSearch(query: KeywordSearchQuery): PagedResult<KeywordSearchMatch>
}

interface SemanticSearchUseCase {
    fun semanticSearch(query: SemanticSearchQuery): PagedResult<VectorSearchMatch>

    /** Internal handoff for hybrid search so its fingerprint and semantic arm use one exact target. */
    fun activeEmbeddingSetId(): EmbeddingSetId =
        throw UnsupportedOperationException("Active embedding target is not available")

    /**
     * Searches only when the active target remains the one used to bind the caller's cursor.
     */
    fun semanticSearch(
        query: SemanticSearchQuery,
        expectedActiveEmbeddingSetId: EmbeddingSetId,
    ): PagedResult<VectorSearchMatch> =
        throw UnsupportedOperationException("Active embedding target cannot be pinned")
}

interface VectorSearchUseCase {
    fun vectorSearch(query: VectorSearchQuery): PagedResult<VectorSearchMatch>
}

/** Read and recovery boundary for localhost-only embedding job operations. */
interface EmbeddingJobManagementUseCase {
    fun findEmbeddingJobs(query: FindEmbeddingJobsQuery): PagedResult<EmbeddingJob>

    fun findEmbeddingJob(jobId: EmbeddingJobId): EmbeddingJob

    fun retryEmbeddingJob(jobId: EmbeddingJobId): EmbeddingJob
}

interface HybridSearchUseCase {
    fun hybridSearch(query: HybridSearchQuery): PagedResult<HybridSearchMatch>
}

interface SaveArtifactGraphSnapshotUseCase {
    fun saveArtifactGraphSnapshot(command: SaveArtifactGraphSnapshotCommand): ArtifactGraphSnapshotResult
}

interface FindArtifactGraphNodesUseCase {
    fun findGraphNodes(query: GraphNodeSearchQuery): List<ArtifactNode>
}

interface TraceArtifactGraphUseCase {
    fun traceGraph(query: GraphTraceQuery): ArtifactTrace
}
