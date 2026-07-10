package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactsUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactGraphNodesUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.TraceArtifactGraphUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.HybridSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.KeywordSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.VectorSearchUseCase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class QueryRestController(
    private val findArtifactsUseCase: FindArtifactsUseCase,
    private val keywordSearchUseCase: KeywordSearchUseCase,
    private val vectorSearchUseCase: VectorSearchUseCase,
    private val hybridSearchUseCase: HybridSearchUseCase,
    private val findArtifactGraphNodesUseCase: FindArtifactGraphNodesUseCase,
    private val traceArtifactGraphUseCase: TraceArtifactGraphUseCase,
) {
    @GetMapping("/artifacts")
    fun findArtifacts(
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) iterationId: String?,
        @RequestParam(required = false) sourceProjectId: String?,
        @RequestParam(required = false) sourceIterationId: String?,
        @RequestParam(required = false) sourceDocumentId: String?,
        @RequestParam(required = false) sourceTaskGraphId: String?,
        @RequestParam(required = false) sourceTaskId: String?,
        @RequestParam(required = false) sourceRunId: String?,
        @RequestParam(required = false) artifactType: String?,
        @RequestParam(required = false) sourcePath: String?,
        @RequestParam(required = false) taskId: String?,
        @RequestParam(required = false) runId: String?,
        @RequestParam(required = false) contentHash: String?,
        @RequestParam(required = false) sourceReferenceCanonicalServerId: String?,
        @RequestParam(required = false) sourceReferenceUri: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): PagedResponse<ArtifactLookupResponse> =
        findArtifactsUseCase.findArtifacts(
            ArtifactLookupRequest(
                projectId = projectId,
                iterationId = iterationId,
                sourceProjectId = sourceProjectId,
                sourceIterationId = sourceIterationId,
                sourceDocumentId = sourceDocumentId,
                sourceTaskGraphId = sourceTaskGraphId,
                sourceTaskId = sourceTaskId,
                sourceRunId = sourceRunId,
                artifactType = artifactType,
                sourcePath = sourcePath,
                taskId = taskId,
                runId = runId,
                contentHash = contentHash,
                sourceReferenceCanonicalServerId = sourceReferenceCanonicalServerId,
                sourceReferenceUri = sourceReferenceUri,
                limit = limit,
                cursor = cursor,
            ).toQuery(),
        ).toRestPage { it.toLookupResponse() }

    @GetMapping("/search/keyword")
    fun keywordSearch(
        @RequestParam(name = "q", required = false) q: String?,
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) iterationId: String?,
        @RequestParam(required = false) artifactType: String?,
        @RequestParam(required = false) sourcePath: String?,
        @RequestParam(required = false) taskId: String?,
        @RequestParam(required = false) runId: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): PagedResponse<KeywordSearchResponse> =
        keywordSearchUseCase.keywordSearch(
            KeywordSearchRequest(
                q = q,
                projectId = projectId,
                iterationId = iterationId,
                artifactType = artifactType,
                sourcePath = sourcePath,
                taskId = taskId,
                runId = runId,
                limit = limit,
                cursor = cursor,
            ).toQuery(),
        ).toRestPage { it.toResponse() }

    @GetMapping("/graph/nodes")
    fun findGraphNodes(
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) iterationId: String?,
        @RequestParam(required = false) nodeKind: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) limit: Int?,
    ): List<ArtifactGraphNodeResponse> =
        findArtifactGraphNodesUseCase.findGraphNodes(graphNodeSearchQuery(projectId, iterationId, nodeKind, query, limit)).map { it.toResponse() }

    @GetMapping("/graph/trace")
    fun traceGraph(
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) naturalKey: String?,
        @RequestParam(required = false) iterationId: String?,
        @RequestParam(required = false) direction: String?,
        @RequestParam(required = false) maxDepth: Int?,
    ): ArtifactTraceResponse =
        traceArtifactGraphUseCase.traceGraph(graphTraceQuery(projectId, naturalKey, iterationId, direction, maxDepth)).toResponse()

    @PostMapping("/search/vector")
    fun vectorSearch(@RequestBody request: VectorSearchRequest): PagedResponse<VectorSearchResponse> =
        vectorSearchUseCase.vectorSearch(request.toQuery()).toRestPage { it.toResponse() }

    @PostMapping("/search/hybrid")
    fun hybridSearch(@RequestBody request: HybridSearchRequest): PagedResponse<HybridSearchResponse> =
        hybridSearchUseCase.hybridSearch(request.toQuery()).toRestPage { it.toResponse() }
}

@RestController
@RequestMapping("/api")
class HealthRestController {
    @GetMapping("/health")
    fun health(): HealthResponse = HealthResponse(status = "UP")
}
