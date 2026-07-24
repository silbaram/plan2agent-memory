package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.port.`in`.EmbeddingJobManagementUseCase
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/embedding-jobs")
class EmbeddingJobRestController(
    private val embeddingJobManagementUseCase: EmbeddingJobManagementUseCase,
) {
    @GetMapping
    fun findEmbeddingJobs(
        @RequestParam(name = "status", required = false) statuses: List<String>?,
        @RequestParam(required = false) chunkId: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): PagedResponse<EmbeddingJobResponse> =
        embeddingJobManagementUseCase.findEmbeddingJobs(
            EmbeddingJobListRequest(
                statuses = statuses.orEmpty(),
                chunkId = chunkId,
                limit = limit,
                cursor = cursor,
            ).toQuery(),
        ).toRestPage { it.toResponse() }

    @GetMapping("/{jobId}")
    fun findEmbeddingJob(@PathVariable jobId: String): EmbeddingJobResponse =
        embeddingJobManagementUseCase.findEmbeddingJob(EmbeddingJobId(jobId.trim())).toResponse()

    @PostMapping("/{jobId}/retry")
    fun retryEmbeddingJob(@PathVariable jobId: String): EmbeddingJobResponse =
        embeddingJobManagementUseCase.retryEmbeddingJob(EmbeddingJobId(jobId.trim())).toResponse()
}
