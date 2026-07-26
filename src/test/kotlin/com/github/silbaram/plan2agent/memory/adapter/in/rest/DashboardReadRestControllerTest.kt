@file:Suppress("DEPRECATION")

package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactDetailUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.FindIterationSummariesUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.FindProjectSummariesUseCase
import com.github.silbaram.plan2agent.memory.application.port.out.DashboardReadPort
import com.github.silbaram.plan2agent.memory.application.usecase.DashboardReadUseCaseService
import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactDetailQuery
import com.github.silbaram.plan2agent.memory.application.usecase.FindIterationSummariesQuery
import com.github.silbaram.plan2agent.memory.application.usecase.FindProjectSummariesQuery
import com.github.silbaram.plan2agent.memory.application.usecase.IterationSummaryPageQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.ProjectSummaryPageQuery
import com.github.silbaram.plan2agent.memory.config.JacksonObjectMapperConfig
import com.github.silbaram.plan2agent.memory.domain.ArtifactRef
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactDetail
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactIdentity
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactLineage
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactSource
import com.github.silbaram.plan2agent.memory.domain.readmodel.IterationSummary
import com.github.silbaram.plan2agent.memory.domain.readmodel.ProjectSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class DashboardReadRestControllerTest {
    private val findProjects = FakeFindProjectSummariesUseCase()
    private val findIterations = FakeFindIterationSummariesUseCase()
    private val findArtifactDetail = FakeFindArtifactDetailUseCase()
    private val controller = DashboardReadRestController(
        findProjectSummariesUseCase = findProjects,
        findIterationSummariesUseCase = findIterations,
        findArtifactDetailUseCase = findArtifactDetail,
    )

    @Test
    fun `project list maps keyset page requests and response items`() {
        val project = projectSummary()
        findProjects.result = PagedResult(
            items = listOf(project),
            nextCursor = "next-project-cursor",
        )

        val response = controller.findProjects(limit = 25, cursor = "project-cursor")

        assertThat(findProjects.received).isEqualTo(
            FindProjectSummariesQuery(
                limit = 25,
                cursor = "project-cursor",
            ),
        )
        assertThat(response.items).containsExactly(
            DashboardProjectSummaryResponse(
                projectId = DashboardTestIds.projectId.value,
                sourceProjectId = "source-project",
                name = "Memory",
                canonicalServerId = "local-memory",
                rootPath = "/workspace/memory",
                sourceReference = DashboardSourceReferenceResponse(
                    canonicalServerId = "local-memory",
                    uri = "file:///workspace/memory",
                    path = ".",
                ),
                createdAt = capturedAt,
                updatedAt = updatedAt,
                metadata = mapOf("owner" to "platform"),
            ),
        )
        assertThat(response.nextCursor).isEqualTo("next-project-cursor")
    }

    @Test
    fun `empty project and iteration lists return items with no next cursor`() {
        findProjects.result = PagedResult(emptyList())
        findIterations.result = PagedResult(emptyList())

        val projects = controller.findProjects(limit = null, cursor = null)
        val iterations = controller.findProjectIterations(
            projectId = DashboardTestIds.projectId.value,
            limit = null,
            cursor = null,
        )

        assertThat(projects.items).isEmpty()
        assertThat(projects.nextCursor).isNull()
        assertThat(iterations.items).isEmpty()
        assertThat(iterations.nextCursor).isNull()
        assertThat(findProjects.received).isEqualTo(FindProjectSummariesQuery())
        assertThat(findIterations.received).isEqualTo(
            FindIterationSummariesQuery(projectId = DashboardTestIds.projectId),
        )
    }

    @Test
    fun `artifact detail maps source lineage raw content and metadata`() {
        findArtifactDetail.result = artifactDetail()

        val response = controller.findArtifactDetail(
            artifactType = "task",
            artifactId = DashboardTestIds.taskId.value,
        )

        assertThat(findArtifactDetail.received).isEqualTo(
            FindArtifactDetailQuery(
                artifactType = ArtifactType.TASK,
                artifactId = DashboardTestIds.taskId.value,
            ),
        )
        assertThat(response.source.sourceDocumentId).isEqualTo("source-document")
        assertThat(response.source.sourceReference?.uri)
            .isEqualTo("file:///workspace/memory/.plan2agent/tasks/task.json")
        assertThat(response.lineage.documentId).isEqualTo(DashboardTestIds.documentId.value)
        assertThat(response.lineage.taskGraphId).isEqualTo(DashboardTestIds.taskGraphId.value)
        assertThat(response.lineage.artifactRefs)
            .containsExactly(
                DashboardArtifactReferenceResponse(
                    artifactType = "TASK_GRAPH",
                    artifactId = DashboardTestIds.taskGraphId.value,
                ),
            )
        assertThat(response.mediaType).isEqualTo("application/json")
        assertThat(response.rawContent).isEqualTo("{\"id\":\"task\"}")
        assertThat(response.metadata).containsEntry("source", "p2a")
    }

    @Test
    fun `iteration cursor is bound to its project before the persistence read`() {
        val readPort = CursorBindingDashboardReadPort()
        val dashboardReadUseCase = DashboardReadUseCaseService(readPort)
        val strictController = DashboardReadRestController(
            findProjectSummariesUseCase = findProjects,
            findIterationSummariesUseCase = dashboardReadUseCase,
            findArtifactDetailUseCase = findArtifactDetail,
        )
        val firstPage = strictController.findProjectIterations(
            projectId = DashboardTestIds.projectId.value,
            limit = 1,
            cursor = null,
        )

        dashboardReadMockMvc(strictController).perform(
            get("/api/projects/${DashboardTestIds.otherProjectId.value}/iterations")
                .param("limit", "1")
                .param("cursor", requireNotNull(firstPage.nextCursor)),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation_error"))

        assertThat(readPort.iterationQueries).containsExactly(
            IterationSummaryPageQuery(
                projectId = DashboardTestIds.projectId,
                limit = 1,
            ),
        )
    }

    @Test
    fun `invalid identifiers types cursors and page limits return safe validation errors without use case calls`() {
        val mockMvc = dashboardReadMockMvc(controller)

        listOf(
            get("/api/projects/not-an-id/iterations"),
            get("/api/projects/${DashboardTestIds.projectId.value}/iterations").param("cursor", "invalid cursor"),
            get("/api/projects").param("limit", "201"),
            get("/api/artifacts/PROJECT/${DashboardTestIds.taskId.value}"),
            get("/api/artifacts/TASK/not-an-id"),
        ).forEach { request ->
            mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("invalid cursor"))))
        }

        assertThat(findProjects.callCount).isZero()
        assertThat(findIterations.callCount).isZero()
        assertThat(findArtifactDetail.callCount).isZero()
    }

    @Test
    fun `missing approved artifact returns a safe not found response`() {
        findArtifactDetail.failure = NoSuchElementException("credential=do-not-expose")

        dashboardReadMockMvc(controller).perform(
            get("/api/artifacts/TASK/${DashboardTestIds.taskId.value}"),
        )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"))
            .andExpect(jsonPath("$.message").value("Requested resource was not found"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("credential"))))

        assertThat(findArtifactDetail.callCount).isEqualTo(1)
    }

    private fun projectSummary(): ProjectSummary =
        ProjectSummary(
            projectId = DashboardTestIds.projectId,
            sourceProjectId = SourceProjectId("source-project"),
            name = "Memory",
            canonicalServerId = CanonicalServerId("local-memory"),
            rootPath = "/workspace/memory",
            sourceReference = SourceReference(
                canonicalServerId = CanonicalServerId("local-memory"),
                uri = "file:///workspace/memory",
                path = ".",
            ),
            createdAt = capturedAt,
            updatedAt = updatedAt,
            metadata = mapOf("owner" to "platform"),
        )

    private fun artifactDetail(): ArtifactDetail =
        ArtifactDetail(
            artifactType = ArtifactType.TASK,
            artifactId = DashboardTestIds.taskId.value,
            projectId = DashboardTestIds.projectId,
            iterationId = DashboardTestIds.iterationId,
            title = "Expose dashboard read API",
            source = ArtifactSource(
                sourceProjectId = SourceProjectId("source-project"),
                sourceIterationId = SourceIterationId("source-iteration"),
                sourceDocumentId = SourceDocumentId("source-document"),
                sourceTaskGraphId = com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId("source-graph"),
                sourceTaskId = com.github.silbaram.plan2agent.memory.domain.SourceTaskId("source-task"),
                sourcePath = ".plan2agent/tasks/task.json",
                sourceReference = SourceReference(
                    canonicalServerId = CanonicalServerId("local-memory"),
                    uri = "file:///workspace/memory/.plan2agent/tasks/task.json",
                ),
            ),
            lineage = ArtifactLineage(
                projectId = DashboardTestIds.projectId,
                iterationId = DashboardTestIds.iterationId,
                documentId = DashboardTestIds.documentId,
                taskGraphId = DashboardTestIds.taskGraphId,
                taskId = DashboardTestIds.taskId,
                contentHash = ContentHash("content-hash"),
                artifactRefs = listOf(
                    ArtifactRef(
                        artifactType = ArtifactType.TASK_GRAPH,
                        artifactId = DashboardTestIds.taskGraphId.value,
                    ),
                ),
            ),
            mediaType = "application/json",
            rawContent = "{\"id\":\"task\"}",
            createdAt = capturedAt,
            updatedAt = updatedAt,
            metadata = mapOf("source" to "p2a"),
        )

    private companion object {
        val capturedAt: Instant = Instant.parse("2026-07-26T00:00:00Z")
        val updatedAt: Instant = Instant.parse("2026-07-26T01:00:00Z")
    }
}

private fun dashboardReadMockMvc(controller: DashboardReadRestController) =
    MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(RestExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(JacksonObjectMapperConfig().objectMapper()))
        .build()

private class FakeFindProjectSummariesUseCase : FindProjectSummariesUseCase {
    var callCount: Int = 0
    var received: FindProjectSummariesQuery? = null
    var result: PagedResult<ProjectSummary> = PagedResult(emptyList())

    override fun findProjectSummaries(query: FindProjectSummariesQuery): PagedResult<ProjectSummary> {
        callCount += 1
        received = query
        return result
    }
}

private class FakeFindIterationSummariesUseCase : FindIterationSummariesUseCase {
    var callCount: Int = 0
    var received: FindIterationSummariesQuery? = null
    var result: PagedResult<IterationSummary> = PagedResult(emptyList())

    override fun findIterationSummaries(query: FindIterationSummariesQuery): PagedResult<IterationSummary> {
        callCount += 1
        received = query
        return result
    }
}

private class FakeFindArtifactDetailUseCase : FindArtifactDetailUseCase {
    var callCount: Int = 0
    var received: FindArtifactDetailQuery? = null
    var result: ArtifactDetail? = null
    var failure: RuntimeException? = null

    override fun findArtifactDetail(query: FindArtifactDetailQuery): ArtifactDetail {
        callCount += 1
        received = query
        failure?.let { throw it }
        return requireNotNull(result)
    }
}

private class CursorBindingDashboardReadPort : DashboardReadPort {
    val iterationQueries = mutableListOf<IterationSummaryPageQuery>()

    override fun findProjectSummaries(query: ProjectSummaryPageQuery): PagedResult<ProjectSummary> =
        PagedResult(emptyList())

    override fun findIterationSummaries(query: IterationSummaryPageQuery): PagedResult<IterationSummary> {
        iterationQueries += query
        return PagedResult(
            items = emptyList(),
            nextCursor = if (query.cursor == null) "iteration-keyset" else null,
        )
    }

    override fun findArtifactDetail(identity: ArtifactIdentity): ArtifactDetail? = null
}

private data object DashboardTestIds {
    val projectId = ProjectId("11111111-1111-4111-8111-111111111111")
    val otherProjectId = ProjectId("22222222-2222-4222-8222-222222222222")
    val iterationId = IterationId("33333333-3333-4333-8333-333333333333")
    val documentId = DocumentId("44444444-4444-4444-8444-444444444444")
    val taskGraphId = TaskGraphId("55555555-5555-4555-8555-555555555555")
    val taskId = TaskId("66666666-6666-4666-8666-666666666666")
}
