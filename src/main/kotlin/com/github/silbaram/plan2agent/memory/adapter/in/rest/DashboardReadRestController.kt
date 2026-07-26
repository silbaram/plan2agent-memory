package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactDetailUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.FindIterationSummariesUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.FindProjectSummariesUseCase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class DashboardReadRestController(
    private val findProjectSummariesUseCase: FindProjectSummariesUseCase,
    private val findIterationSummariesUseCase: FindIterationSummariesUseCase,
    private val findArtifactDetailUseCase: FindArtifactDetailUseCase,
) {
    @GetMapping("/projects")
    fun findProjects(
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): PagedResponse<DashboardProjectSummaryResponse> =
        findProjectSummariesUseCase.findProjectSummaries(
            DashboardProjectListRequest(
                limit = limit,
                cursor = cursor,
            ).toQuery(),
        ).toRestPage { it.toDashboardResponse() }

    @GetMapping("/projects/{projectId}/iterations")
    fun findProjectIterations(
        @PathVariable projectId: String,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): PagedResponse<DashboardIterationSummaryResponse> =
        findIterationSummariesUseCase.findIterationSummaries(
            DashboardIterationListRequest(
                projectId = projectId,
                limit = limit,
                cursor = cursor,
            ).toQuery(),
        ).toRestPage { it.toDashboardResponse() }

    @GetMapping("/artifacts/{artifactType}/{artifactId}")
    fun findArtifactDetail(
        @PathVariable artifactType: String,
        @PathVariable artifactId: String,
    ): DashboardArtifactDetailResponse =
        findArtifactDetailUseCase.findArtifactDetail(
            DashboardArtifactDetailRequest(
                artifactType = artifactType,
                artifactId = artifactId,
            ).toQuery(),
        ).toDashboardResponse()
}
