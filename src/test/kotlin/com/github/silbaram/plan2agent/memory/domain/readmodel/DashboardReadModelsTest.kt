package com.github.silbaram.plan2agent.memory.domain.readmodel

import com.github.silbaram.plan2agent.memory.domain.ArtifactRef
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.SourceRunId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskId
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant

class DashboardReadModelsTest {
    @Test
    fun `project and iteration summaries retain stored domain values`() {
        val capturedAt = Instant.parse("2026-07-25T00:00:00Z")
        val project = ProjectSummary(
            projectId = ProjectId("project-1"),
            sourceProjectId = SourceProjectId("source-project-1"),
            name = "Plan2Agent memory",
            canonicalServerId = CanonicalServerId("local"),
            rootPath = "/workspace/plan2agent-memory",
            sourceReference = sourceReference(),
            createdAt = capturedAt,
            metadata = mapOf("owner" to "qoo10"),
        )
        val iteration = IterationSummary(
            iterationId = IterationId("iteration-1"),
            projectId = project.projectId,
            sourceIterationId = SourceIterationId("source-iteration-1"),
            label = "v3-owner-dashboard",
            status = IterationStatus.ACTIVE,
            sourceReference = sourceReference(),
            createdAt = capturedAt,
        )

        assertThat(project.projectId).isEqualTo(ProjectId("project-1"))
        assertThat(project.sourceReference?.uri).isEqualTo("file:///workspace/project.json")
        assertThat(iteration.projectId).isEqualTo(project.projectId)
        assertThat(iteration.status).isEqualTo(IterationStatus.ACTIVE)
    }

    @Test
    fun `artifact detail preserves composite identity stored source lineage and raw content`() {
        val projectId = ProjectId("project-1")
        val iterationId = IterationId("iteration-1")
        val source = ArtifactSource(
            sourceProjectId = SourceProjectId("source-project-1"),
            sourceIterationId = SourceIterationId("source-iteration-1"),
            sourceDocumentId = SourceDocumentId("source-document-1"),
            sourceTaskGraphId = SourceTaskGraphId("source-graph-1"),
            sourceTaskId = SourceTaskId("source-task-1"),
            sourceRunId = SourceRunId("source-run-1"),
            sourcePath = ".plan2agent/artifacts/task-1.json",
            sourceReference = sourceReference(),
        )
        val lineage = ArtifactLineage(
            projectId = projectId,
            iterationId = iterationId,
            documentId = DocumentId("document-1"),
            taskGraphId = TaskGraphId("graph-1"),
            taskId = TaskId("task-1"),
            runId = RunId("run-1"),
            contentHash = ContentHash("content-hash-1"),
            snapshotVersion = 2,
            artifactRefs = listOf(ArtifactRef(ArtifactType.TASK_GRAPH, "graph-1")),
        )
        val detail = ArtifactDetail(
            artifactType = ArtifactType.TASK,
            artifactId = "task-1",
            projectId = projectId,
            iterationId = iterationId,
            title = "Implement dashboard read models",
            source = source,
            lineage = lineage,
            mediaType = "application/json",
            rawContent = "{\"id\":\"task-1\"}",
        )

        assertThat(detail.identity).isEqualTo(ArtifactIdentity(ArtifactType.TASK, "task-1"))
        assertThat(detail.source).isEqualTo(source)
        assertThat(detail.lineage).isEqualTo(lineage)
        assertThat(detail.mediaType).isEqualTo("application/json")
        assertThat(detail.rawContent).isEqualTo("{\"id\":\"task-1\"}")
    }

    @Test
    fun `document chunks are excluded from dashboard tree and raw detail`() {
        assertThat(DashboardArtifactPolicy.isTreeAndDetailArtifact(ArtifactType.DOCUMENT_CHUNK)).isFalse()
        assertThat(DashboardArtifactPolicy.isTreeAndDetailArtifact(ArtifactType.DOCUMENT_SNAPSHOT)).isTrue()

        assertThatIllegalArgumentException()
            .isThrownBy {
                ArtifactDetail(
                    artifactType = ArtifactType.DOCUMENT_CHUNK,
                    artifactId = "chunk-1",
                    projectId = ProjectId("project-1"),
                    title = "Hidden chunk",
                    source = ArtifactSource(),
                    lineage = ArtifactLineage(projectId = ProjectId("project-1")),
                    mediaType = "text/plain",
                    rawContent = "not a dashboard artifact",
                )
            }
            .withMessage("ArtifactDetail does not support dashboard artifact type DOCUMENT_CHUNK")
    }

    private fun sourceReference(): SourceReference =
        SourceReference(
            canonicalServerId = CanonicalServerId("local"),
            uri = "file:///workspace/project.json",
            path = "project.json",
            startLine = 1,
            endLine = 3,
        )
}
