package com.github.silbaram.plan2agent.memory.application.port.`in`

import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.KeywordSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.RegisterIterationCommand
import com.github.silbaram.plan2agent.memory.application.usecase.RegisterProjectCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentChunksCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentSnapshotCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveRunRecordCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveTaskGraphCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveTasksCommand
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
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

interface VectorSearchUseCase {
    fun vectorSearch(query: VectorSearchQuery): PagedResult<VectorSearchMatch>
}
