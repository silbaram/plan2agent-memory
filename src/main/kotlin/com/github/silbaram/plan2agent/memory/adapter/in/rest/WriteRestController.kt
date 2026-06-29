package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.port.`in`.RegisterIterationUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.RegisterProjectUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.SaveDocumentChunksUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.SaveDocumentSnapshotUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.SaveRunRecordUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.SaveTaskGraphUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.SaveTasksUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class WriteRestController(
    private val registerProjectUseCase: RegisterProjectUseCase,
    private val registerIterationUseCase: RegisterIterationUseCase,
    private val saveDocumentSnapshotUseCase: SaveDocumentSnapshotUseCase,
    private val saveTaskGraphUseCase: SaveTaskGraphUseCase,
    private val saveTasksUseCase: SaveTasksUseCase,
    private val saveRunRecordUseCase: SaveRunRecordUseCase,
    private val saveDocumentChunksUseCase: SaveDocumentChunksUseCase,
) {
    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    fun upsertProject(@RequestBody request: ProjectWriteRequest): ProjectResponse =
        registerProjectUseCase.registerProject(request.toCommand()).toResponse()

    @PostMapping("/projects/{projectId}/iterations")
    @ResponseStatus(HttpStatus.CREATED)
    fun upsertIteration(
        @PathVariable projectId: String,
        @RequestBody request: IterationWriteRequest,
    ): IterationResponse =
        registerIterationUseCase.registerIteration(request.toCommand(projectId)).toResponse()

    @PostMapping("/documents/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveDocumentSnapshot(@RequestBody request: DocumentSnapshotWriteRequest): DocumentSnapshotResponse =
        saveDocumentSnapshotUseCase.saveDocumentSnapshot(request.toCommand()).toResponse()

    @PostMapping("/task-graphs")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveTaskGraph(@RequestBody request: TaskGraphWriteRequest): TaskGraphResponse =
        saveTaskGraphUseCase.saveTaskGraph(request.toCommand()).toResponse()

    @PostMapping("/tasks/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveTasks(@RequestBody request: TasksBulkWriteRequest): List<TaskResponse> =
        saveTasksUseCase.saveTasks(request.toCommand()).map { it.toResponse() }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveRun(@RequestBody request: RunRecordWriteRequest): RunRecordResponse =
        saveRunRecordUseCase.saveRunRecord(request.toCommand()).toResponse()

    @PostMapping("/document-chunks/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveDocumentChunks(@RequestBody request: DocumentChunksBulkWriteRequest): List<DocumentChunkResponse> =
        saveDocumentChunksUseCase.saveDocumentChunks(request.toCommand()).map { it.toResponse() }
}
