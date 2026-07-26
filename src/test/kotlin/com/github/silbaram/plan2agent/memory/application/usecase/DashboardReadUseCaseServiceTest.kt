package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.out.DashboardReadPort
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
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactDetail
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactIdentity
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactLineage
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactSource
import com.github.silbaram.plan2agent.memory.domain.readmodel.IterationSummary
import com.github.silbaram.plan2agent.memory.domain.readmodel.ProjectSummary
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class DashboardReadUseCaseServiceTest {
    private val port = FakeDashboardReadPort()
    private val service = DashboardReadUseCaseService(port)

    @Test
    fun `project list returns stable pages and wraps the storage continuation cursor`() {
        port.projectPages[null] = PagedResult(listOf(projectSummary("project-1")), nextCursor = "project-1-keyset")
        port.projectPages["project-1-keyset"] = PagedResult(listOf(projectSummary("project-2")))

        val firstPage = service.findProjectSummaries(FindProjectSummariesQuery(limit = 1))
        val secondPage = service.findProjectSummaries(
            FindProjectSummariesQuery(limit = 1, cursor = requireNotNull(firstPage.nextCursor)),
        )

        assertThat(firstPage.items.map { it.projectId.value }).containsExactly("project-1")
        assertThat(firstPage.nextCursor).isNotBlank()
        assertThat(secondPage.items.map { it.projectId.value }).containsExactly("project-2")
        assertThat(secondPage.nextCursor).isNull()
        assertThat(port.projectQueries).containsExactly(
            ProjectSummaryPageQuery(limit = 1),
            ProjectSummaryPageQuery(limit = 1, cursor = "project-1-keyset"),
        )
    }

    @Test
    fun `empty hierarchy lists have no next cursor`() {
        port.projectPages[null] = PagedResult(emptyList())
        port.iterationPages[ProjectId("project-1") to null] = PagedResult(emptyList())

        val projects = service.findProjectSummaries(FindProjectSummariesQuery())
        val iterations = service.findIterationSummaries(FindIterationSummariesQuery(ProjectId("project-1")))

        assertThat(projects.items).isEmpty()
        assertThat(projects.nextCursor).isNull()
        assertThat(iterations.items).isEmpty()
        assertThat(iterations.nextCursor).isNull()
    }

    @Test
    fun `iteration cursor is bound to its parent project before the port is called`() {
        val projectOne = ProjectId("project-1")
        val projectTwo = ProjectId("project-2")
        port.iterationPages[projectOne to null] = PagedResult(
            listOf(iterationSummary(projectOne, "iteration-1")),
            nextCursor = "iteration-1-keyset",
        )

        val firstPage = service.findIterationSummaries(FindIterationSummariesQuery(projectOne, limit = 1))

        assertThatThrownBy {
            service.findIterationSummaries(
                FindIterationSummariesQuery(projectTwo, limit = 1, cursor = requireNotNull(firstPage.nextCursor)),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("cursor does not match this request")
        assertThat(port.iterationQueries).containsExactly(
            IterationSummaryPageQuery(projectId = projectOne, limit = 1),
        )
    }

    @Test
    fun `artifact detail returns stored source lineage media type metadata and raw content`() {
        val detail = artifactDetail()
        port.artifactDetails[detail.identity] = detail

        val result = service.findArtifactDetail(
            FindArtifactDetailQuery(artifactType = ArtifactType.TASK, artifactId = "task-1"),
        )

        assertThat(result).isEqualTo(detail)
        assertThat(result.source.sourceDocumentId).isEqualTo(SourceDocumentId("source-document-1"))
        assertThat(result.lineage.artifactRefs).containsExactly(ArtifactRef(ArtifactType.TASK_GRAPH, "graph-1"))
        assertThat(result.mediaType).isEqualTo("application/json")
        assertThat(result.rawContent).isEqualTo("{\"id\":\"task-1\"}")
        assertThat(result.metadata).containsEntry("source", "p2a")
        assertThat(port.detailQueries).containsExactly(ArtifactIdentity(ArtifactType.TASK, "task-1"))
    }

    @Test
    fun `detail rejects excluded types and distinguishes a missing supported artifact`() {
        listOf(ArtifactType.DOCUMENT_CHUNK, ArtifactType.PROJECT, ArtifactType.ITERATION).forEach { artifactType ->
            assertThatThrownBy {
                service.findArtifactDetail(FindArtifactDetailQuery(artifactType, "hidden"))
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Artifact type ${artifactType.name} is not supported by dashboard detail")
        }
        assertThat(port.detailQueries).isEmpty()

        assertThatThrownBy {
            service.findArtifactDetail(FindArtifactDetailQuery(ArtifactType.RUN_RECORD, "missing-run"))
        }
            .isInstanceOf(NoSuchElementException::class.java)
            .hasMessage("Artifact RUN_RECORD/missing-run was not found")
        assertThat(port.detailQueries).containsExactly(ArtifactIdentity(ArtifactType.RUN_RECORD, "missing-run"))
    }

    private fun projectSummary(projectId: String): ProjectSummary =
        ProjectSummary(
            projectId = ProjectId(projectId),
            sourceProjectId = SourceProjectId("source-$projectId"),
            name = "Project $projectId",
            canonicalServerId = CanonicalServerId("server-$projectId"),
            rootPath = "/workspace/$projectId",
            createdAt = capturedAt,
        )

    private fun iterationSummary(projectId: ProjectId, iterationId: String): IterationSummary =
        IterationSummary(
            iterationId = IterationId(iterationId),
            projectId = projectId,
            sourceIterationId = SourceIterationId("source-$iterationId"),
            label = "Iteration $iterationId",
            status = IterationStatus.ACTIVE,
            createdAt = capturedAt,
        )

    private fun artifactDetail(): ArtifactDetail =
        ArtifactDetail(
            artifactType = ArtifactType.TASK,
            artifactId = "task-1",
            projectId = ProjectId("project-1"),
            iterationId = IterationId("iteration-1"),
            title = "Implement hierarchy query",
            source = ArtifactSource(
                sourceProjectId = SourceProjectId("source-project-1"),
                sourceIterationId = SourceIterationId("source-iteration-1"),
                sourceDocumentId = SourceDocumentId("source-document-1"),
                sourcePath = ".plan2agent/tasks/task-1.json",
                sourceReference = SourceReference(
                    canonicalServerId = CanonicalServerId("task-1"),
                    uri = "file:///workspace/.plan2agent/tasks/task-1.json",
                ),
            ),
            lineage = ArtifactLineage(
                projectId = ProjectId("project-1"),
                iterationId = IterationId("iteration-1"),
                documentId = DocumentId("document-1"),
                taskId = TaskId("task-1"),
                contentHash = ContentHash("content-hash-1"),
                artifactRefs = listOf(ArtifactRef(ArtifactType.TASK_GRAPH, "graph-1")),
            ),
            mediaType = "application/json",
            rawContent = "{\"id\":\"task-1\"}",
            metadata = mapOf("source" to "p2a"),
        )

    private class FakeDashboardReadPort : DashboardReadPort {
        val projectPages = mutableMapOf<String?, PagedResult<ProjectSummary>>()
        val iterationPages = mutableMapOf<Pair<ProjectId, String?>, PagedResult<IterationSummary>>()
        val artifactDetails = mutableMapOf<ArtifactIdentity, ArtifactDetail>()
        val projectQueries = mutableListOf<ProjectSummaryPageQuery>()
        val iterationQueries = mutableListOf<IterationSummaryPageQuery>()
        val detailQueries = mutableListOf<ArtifactIdentity>()

        override fun findProjectSummaries(query: ProjectSummaryPageQuery): PagedResult<ProjectSummary> {
            projectQueries += query
            return projectPages[query.cursor] ?: PagedResult(emptyList())
        }

        override fun findIterationSummaries(query: IterationSummaryPageQuery): PagedResult<IterationSummary> {
            iterationQueries += query
            return iterationPages[query.projectId to query.cursor] ?: PagedResult(emptyList())
        }

        override fun findArtifactDetail(identity: ArtifactIdentity): ArtifactDetail? {
            detailQueries += identity
            return artifactDetails[identity]
        }
    }

    private companion object {
        val capturedAt: Instant = Instant.parse("2026-07-26T00:00:00Z")
    }
}
