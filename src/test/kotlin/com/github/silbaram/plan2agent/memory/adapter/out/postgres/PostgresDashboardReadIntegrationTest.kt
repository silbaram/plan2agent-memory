package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.github.silbaram.plan2agent.memory.application.port.out.DashboardReadPort
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentSnapshotStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.IterationStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ProjectStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.RunRecordStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskGraphStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskStorePort
import com.github.silbaram.plan2agent.memory.application.usecase.IterationSummaryPageQuery
import com.github.silbaram.plan2agent.memory.application.usecase.MAX_DASHBOARD_PAGE_LIMIT
import com.github.silbaram.plan2agent.memory.application.usecase.ProjectSummaryPageQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactRef
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
import com.github.silbaram.plan2agent.memory.domain.Iteration
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.Project
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.RunRecord
import com.github.silbaram.plan2agent.memory.domain.RunStatus
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.SourceRunId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskId
import com.github.silbaram.plan2agent.memory.domain.Task
import com.github.silbaram.plan2agent.memory.domain.TaskGraph
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.TaskStatus
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactIdentity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Instant

@SpringBootTest(
    properties = [
        "p2a.embedding.provider=none",
        "p2a.memory.embedding.worker.enabled=false",
        "p2a.memory.scheduling.enabled=false",
    ],
)
class PostgresDashboardReadIntegrationTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var dashboardReadPort: DashboardReadPort

    @Autowired
    private lateinit var projectStore: ProjectStorePort

    @Autowired
    private lateinit var iterationStore: IterationStorePort

    @Autowired
    private lateinit var documentSnapshotStore: DocumentSnapshotStorePort

    @Autowired
    private lateinit var taskGraphStore: TaskGraphStorePort

    @Autowired
    private lateinit var taskStore: TaskStorePort

    @Autowired
    private lateinit var runRecordStore: RunRecordStorePort

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute(
            """
            TRUNCATE TABLE
                artifact_edges,
                artifact_nodes,
                embedding_active_profiles,
                embedding_jobs,
                chunk_embeddings,
                embedding_sets,
                document_chunks,
                runs,
                tasks,
                task_graphs,
                documents,
                iterations,
                projects
            RESTART IDENTITY CASCADE
            """.trimIndent(),
        )
    }

    @Test
    fun `project summaries use deterministic keyset pages without duplicates or omissions`() {
        val oldest = storeProject("00000000-0000-0000-0000-000000000001", "oldest", at("00:00:00"))
        val tiedLower = storeProject("00000000-0000-0000-0000-000000000002", "tied-lower", at("00:01:00"))
        val tiedHigher = storeProject("00000000-0000-0000-0000-000000000003", "tied-higher", at("00:01:00"))
        val newest = storeProject("00000000-0000-0000-0000-000000000004", "newest", at("00:02:00"))

        val firstPage = dashboardReadPort.findProjectSummaries(ProjectSummaryPageQuery(limit = 2))
        val secondPage = dashboardReadPort.findProjectSummaries(
            ProjectSummaryPageQuery(limit = 2, cursor = requireNotNull(firstPage.nextCursor)),
        )

        assertThat(firstPage.items.map { it.projectId }).containsExactly(newest.id, tiedHigher.id)
        assertThat(secondPage.items.map { it.projectId }).containsExactly(tiedLower.id, oldest.id)
        assertThat(secondPage.nextCursor).isNull()
        assertThat((firstPage.items + secondPage.items).map { it.projectId })
            .containsExactly(newest.id, tiedHigher.id, tiedLower.id, oldest.id)
            .doesNotHaveDuplicates()
    }

    @Test
    fun `project summaries return no cursor for empty and exhausted pages and enforce the page bound`() {
        val empty = dashboardReadPort.findProjectSummaries(ProjectSummaryPageQuery(limit = 1))

        assertThat(empty.items).isEmpty()
        assertThat(empty.nextCursor).isNull()
        assertThatThrownBy {
            dashboardReadPort.findProjectSummaries(ProjectSummaryPageQuery(limit = 0))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Dashboard page limit must be between 1 and $MAX_DASHBOARD_PAGE_LIMIT")
        assertThatThrownBy {
            dashboardReadPort.findProjectSummaries(ProjectSummaryPageQuery(limit = MAX_DASHBOARD_PAGE_LIMIT + 1))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Dashboard page limit must be between 1 and $MAX_DASHBOARD_PAGE_LIMIT")

        val onlyProject = storeProject("00000000-0000-0000-0000-000000000010", "only", at("00:00:00"))
        val exhausted = dashboardReadPort.findProjectSummaries(ProjectSummaryPageQuery(limit = 1))

        assertThat(exhausted.items.map { it.projectId }).containsExactly(onlyProject.id)
        assertThat(exhausted.nextCursor).isNull()
    }

    @Test
    fun `iteration summaries stay within their parent project and bind their cursor to it`() {
        val firstProject = storeProject("00000000-0000-0000-0000-000000000020", "first-project", at("00:00:00"))
        val secondProject = storeProject("00000000-0000-0000-0000-000000000021", "second-project", at("00:00:00"))
        val oldest = storeIteration(firstProject, "00000000-0000-0000-0000-000000000001", "oldest", at("00:00:00"))
        val tiedLower = storeIteration(firstProject, "00000000-0000-0000-0000-000000000002", "tied-lower", at("00:01:00"))
        val tiedHigher = storeIteration(firstProject, "00000000-0000-0000-0000-000000000003", "tied-higher", at("00:01:00"))
        val secondProjectIteration = storeIteration(
            secondProject,
            "00000000-0000-0000-0000-000000000004",
            "second-project-only",
            at("00:03:00"),
        )

        val firstPage = dashboardReadPort.findIterationSummaries(
            IterationSummaryPageQuery(projectId = firstProject.id, limit = 2),
        )
        val secondPage = dashboardReadPort.findIterationSummaries(
            IterationSummaryPageQuery(
                projectId = firstProject.id,
                limit = 2,
                cursor = requireNotNull(firstPage.nextCursor),
            ),
        )
        val otherProjectPage = dashboardReadPort.findIterationSummaries(
            IterationSummaryPageQuery(projectId = secondProject.id, limit = 2),
        )

        assertThat(firstPage.items.map { it.iterationId }).containsExactly(tiedHigher.id, tiedLower.id)
        assertThat(secondPage.items.map { it.iterationId }).containsExactly(oldest.id)
        assertThat(secondPage.nextCursor).isNull()
        assertThat((firstPage.items + secondPage.items).map { it.iterationId })
            .containsExactly(tiedHigher.id, tiedLower.id, oldest.id)
            .doesNotHaveDuplicates()
        assertThat(otherProjectPage.items.map { it.iterationId }).containsExactly(secondProjectIteration.id)
        assertThat(otherProjectPage.nextCursor).isNull()

        assertThatThrownBy {
            dashboardReadPort.findIterationSummaries(
                IterationSummaryPageQuery(
                    projectId = secondProject.id,
                    limit = 2,
                    cursor = requireNotNull(firstPage.nextCursor),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Dashboard iteration cursor does not match project")
    }

    @Test
    fun `artifact detail uses type plus id and preserves all supported typed payloads`() {
        val fixture = storeDetailFixture()

        val documentDetail = requireNotNull(
            dashboardReadPort.findArtifactDetail(
                ArtifactIdentity(ArtifactType.DOCUMENT_SNAPSHOT, fixture.document.id.value),
            ),
        )
        val taskDetail = requireNotNull(
            dashboardReadPort.findArtifactDetail(
                ArtifactIdentity(ArtifactType.TASK, fixture.task.id.value),
            ),
        )
        val graphDetail = requireNotNull(
            dashboardReadPort.findArtifactDetail(
                ArtifactIdentity(ArtifactType.TASK_GRAPH, fixture.taskGraph.id.value),
            ),
        )
        val runDetail = requireNotNull(
            dashboardReadPort.findArtifactDetail(
                ArtifactIdentity(ArtifactType.RUN_RECORD, fixture.run.id.value),
            ),
        )
        val proposalDetail = requireNotNull(
            dashboardReadPort.findArtifactDetail(
                ArtifactIdentity(ArtifactType.PROPOSAL, fixture.proposal.id.value),
            ),
        )

        assertThat(fixture.document.id.value).isEqualTo(fixture.task.id.value)
        assertThat(documentDetail.artifactType).isEqualTo(ArtifactType.DOCUMENT_SNAPSHOT)
        assertThat(documentDetail.title).isEqualTo(fixture.document.title)
        assertThat(documentDetail.rawContent).isEqualTo(fixture.document.content)
        assertThat(documentDetail.mediaType).isEqualTo("text/markdown")
        assertThat(documentDetail.projectId).isEqualTo(fixture.project.id)
        assertThat(documentDetail.iterationId).isEqualTo(fixture.iteration.id)
        assertThat(documentDetail.source.sourceProjectId).isEqualTo(fixture.project.sourceProjectId)
        assertThat(documentDetail.source.sourceIterationId).isEqualTo(fixture.iteration.sourceIterationId)
        assertThat(documentDetail.source.sourceDocumentId).isEqualTo(fixture.document.sourceDocumentId)
        assertThat(documentDetail.source.sourceReference).isEqualTo(fixture.document.sourceReference)
        assertThat(documentDetail.lineage.documentId).isEqualTo(fixture.document.id)
        assertThat(documentDetail.lineage.contentHash).isEqualTo(fixture.document.contentHash)
        assertThat(documentDetail.lineage.snapshotVersion).isEqualTo(1)
        assertThat(documentDetail.metadata).containsEntry("kind", "document")

        assertThat(taskDetail.artifactType).isEqualTo(ArtifactType.TASK)
        assertThat(taskDetail.title).isEqualTo(fixture.task.title)
        assertThat(taskDetail.mediaType).isEqualTo("application/json")
        assertThat(taskDetail.rawContent).contains("\"task_id\": \"${fixture.task.id.value}\"")
        assertThat(taskDetail.rawContent).contains("\"title\": \"${fixture.task.title}\"")
        assertThat(taskDetail.source.sourceProjectId).isEqualTo(fixture.project.sourceProjectId)
        assertThat(taskDetail.source.sourceIterationId).isEqualTo(fixture.iteration.sourceIterationId)
        assertThat(taskDetail.source.sourceTaskGraphId).isEqualTo(fixture.taskGraph.sourceTaskGraphId)
        assertThat(taskDetail.source.sourceTaskId).isEqualTo(fixture.task.sourceTaskId)
        assertThat(taskDetail.lineage.documentId).isEqualTo(fixture.document.id)
        assertThat(taskDetail.lineage.taskGraphId).isEqualTo(fixture.taskGraph.id)
        assertThat(taskDetail.lineage.taskId).isEqualTo(fixture.task.id)
        assertThat(taskDetail.metadata).containsEntry("kind", "task")

        assertThat(graphDetail.artifactType).isEqualTo(ArtifactType.TASK_GRAPH)
        assertThat(graphDetail.rawContent).isEqualTo(fixture.taskGraph.graphJson)
        assertThat(graphDetail.mediaType).isEqualTo("application/json")
        assertThat(graphDetail.source.sourceDocumentId).isEqualTo(fixture.document.sourceDocumentId)
        assertThat(graphDetail.source.sourceTaskGraphId).isEqualTo(fixture.taskGraph.sourceTaskGraphId)
        assertThat(graphDetail.lineage.documentId).isEqualTo(fixture.document.id)
        assertThat(graphDetail.lineage.taskGraphId).isEqualTo(fixture.taskGraph.id)
        assertThat(graphDetail.lineage.contentHash).isEqualTo(fixture.taskGraph.graphHash)
        assertThat(graphDetail.metadata).containsEntry("kind", "task-graph")

        assertThat(runDetail.artifactType).isEqualTo(ArtifactType.RUN_RECORD)
        assertThat(runDetail.rawContent).isEqualTo(fixture.run.runJson)
        assertThat(runDetail.mediaType).isEqualTo("application/json")
        assertThat(runDetail.source.sourceProjectId).isEqualTo(fixture.project.sourceProjectId)
        assertThat(runDetail.source.sourceIterationId).isEqualTo(fixture.iteration.sourceIterationId)
        assertThat(runDetail.source.sourceTaskGraphId).isEqualTo(fixture.taskGraph.sourceTaskGraphId)
        assertThat(runDetail.source.sourceTaskId).isEqualTo(fixture.task.sourceTaskId)
        assertThat(runDetail.source.sourceRunId).isEqualTo(fixture.run.sourceRunId)
        assertThat(runDetail.lineage.taskGraphId).isEqualTo(fixture.taskGraph.id)
        assertThat(runDetail.lineage.taskId).isEqualTo(fixture.task.id)
        assertThat(runDetail.lineage.runId).isEqualTo(fixture.run.id)
        assertThat(runDetail.lineage.artifactRefs).isEqualTo(fixture.run.artifactRefs)
        assertThat(runDetail.metadata).containsEntry("kind", "run")

        assertThat(proposalDetail.artifactType).isEqualTo(ArtifactType.PROPOSAL)
        assertThat(proposalDetail.rawContent).isEqualTo(fixture.proposal.content)
        assertThat(proposalDetail.mediaType).isEqualTo("application/json")
        assertThat(proposalDetail.source.sourceDocumentId).isEqualTo(fixture.proposal.sourceDocumentId)
        assertThat(proposalDetail.lineage.documentId).isEqualTo(fixture.proposal.id)
        assertThat(proposalDetail.lineage.contentHash).isEqualTo(fixture.proposal.contentHash)
        assertThat(proposalDetail.metadata).containsEntry("kind", "proposal")
    }

    @Test
    fun `artifact detail returns no row for a missing supported identity and rejects excluded types first`() {
        assertThat(
            dashboardReadPort.findArtifactDetail(
                ArtifactIdentity(ArtifactType.RUN_RECORD, "00000000-0000-0000-0000-000000000099"),
            ),
        ).isNull()

        listOf(ArtifactType.PROJECT, ArtifactType.ITERATION, ArtifactType.DOCUMENT_CHUNK).forEach { artifactType ->
            assertThatThrownBy {
                dashboardReadPort.findArtifactDetail(ArtifactIdentity(artifactType, "not-a-uuid"))
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Artifact type ${artifactType.name} is not supported by dashboard detail")
        }
    }

    private fun storeDetailFixture(): DetailFixture {
        val project = storeProject("00000000-0000-0000-0000-000000000030", "detail-project", at("00:00:00"))
        val iteration = storeIteration(project, "00000000-0000-0000-0000-000000000031", "detail-iteration", at("00:01:00"))
        val sharedDocumentAndTaskId = "00000000-0000-0000-0000-000000000032"
        val document = documentSnapshotStore.save(
            DocumentSnapshot(
                id = DocumentId(sharedDocumentAndTaskId),
                projectId = project.id,
                iterationId = iteration.id,
                sourceDocumentId = SourceDocumentId("source-detail-document"),
                sourcePath = "docs/detail.md",
                snapshotVersion = 1,
                artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                title = "Detail document",
                content = "# Detail document\n\nStored markdown.",
                contentHash = ContentHash("detail-document-hash"),
                sourceReference = sourceReference(sharedDocumentAndTaskId, "docs/detail.md"),
                capturedAt = at("00:02:00"),
                createdAt = at("00:02:00"),
                metadata = mapOf("kind" to "document"),
            ),
        )
        val taskGraph = taskGraphStore.save(
            TaskGraph(
                id = TaskGraphId("00000000-0000-0000-0000-000000000033"),
                projectId = project.id,
                iterationId = iteration.id,
                sourceTaskGraphId = SourceTaskGraphId("source-detail-graph"),
                sourceDocumentId = document.sourceDocumentId,
                graphHash = ContentHash("detail-graph-hash"),
                graphJson = "{\"tasks\":[\"$sharedDocumentAndTaskId\"]}",
                taskIds = setOf(TaskId(sharedDocumentAndTaskId)),
                sourceReference = sourceReference("00000000-0000-0000-0000-000000000033", "task-graphs/detail.json"),
                createdAt = at("00:03:00"),
                metadata = mapOf("kind" to "task-graph"),
            ),
        )
        val task = taskStore.saveAll(
            listOf(
                Task(
                    id = TaskId(sharedDocumentAndTaskId),
                    projectId = project.id,
                    iterationId = iteration.id,
                    taskGraphId = taskGraph.id,
                    sourceTaskId = SourceTaskId("source-detail-task"),
                    title = "Detail task",
                    description = "Preserve the stored task payload.",
                    status = TaskStatus.READY,
                    targetArea = "dashboard-detail",
                    acceptanceCriteria = listOf("Expose raw task content"),
                    sourceReference = sourceReference(sharedDocumentAndTaskId, "task-graphs/detail.json#task"),
                    createdAt = at("00:04:00"),
                    metadata = mapOf("kind" to "task"),
                ),
            ),
        ).single()
        val run = runRecordStore.save(
            RunRecord(
                id = RunId("00000000-0000-0000-0000-000000000034"),
                projectId = project.id,
                iterationId = iteration.id,
                taskId = task.id,
                sourceRunId = SourceRunId("source-detail-run"),
                status = RunStatus.FINISHED,
                agentTool = "codex",
                runJson = "{\"status\":\"finished\",\"summary\":\"detail\"}",
                artifactRefs = listOf(ArtifactRef(ArtifactType.DOCUMENT_SNAPSHOT, document.id.value, document.sourcePath)),
                startedAt = at("00:05:00"),
                finishedAt = at("00:06:00"),
                sourceReference = sourceReference("00000000-0000-0000-0000-000000000034", "runs/detail.json"),
                createdAt = at("00:05:00"),
                metadata = mapOf("kind" to "run"),
            ),
        )
        val proposal = documentSnapshotStore.save(
            DocumentSnapshot(
                id = DocumentId("00000000-0000-0000-0000-000000000035"),
                projectId = project.id,
                iterationId = iteration.id,
                sourceDocumentId = SourceDocumentId("source-detail-proposal"),
                sourcePath = ".plan2agent/proposals/detail.json",
                snapshotVersion = 1,
                artifactType = ArtifactType.PROPOSAL,
                title = "Detail proposal",
                content = "{\"proposalId\":\"detail\"}",
                contentHash = ContentHash("detail-proposal-hash"),
                sourceReference = sourceReference(
                    "00000000-0000-0000-0000-000000000035",
                    ".plan2agent/proposals/detail.json",
                ),
                capturedAt = at("00:07:00"),
                createdAt = at("00:07:00"),
                metadata = mapOf("kind" to "proposal"),
            ),
        )
        return DetailFixture(project, iteration, document, taskGraph, task, run, proposal)
    }

    private fun storeProject(id: String, name: String, createdAt: Instant): Project =
        projectStore.save(
            Project(
                id = ProjectId(id),
                sourceProjectId = SourceProjectId("source-$name"),
                name = name,
                canonicalServerId = CanonicalServerId("canonical-$name"),
                rootPath = "/workspace/$name",
                createdAt = createdAt,
            ),
        )

    private fun storeIteration(
        project: Project,
        id: String,
        label: String,
        createdAt: Instant,
    ): Iteration =
        iterationStore.save(
            Iteration(
                id = IterationId(id),
                projectId = project.id,
                sourceIterationId = SourceIterationId("source-$label"),
                label = label,
                status = IterationStatus.ACTIVE,
                createdAt = createdAt,
            ),
        )

    private fun at(time: String): Instant = Instant.parse("2026-07-26T${time}Z")

    private fun sourceReference(canonicalServerId: String, path: String): SourceReference =
        SourceReference(
            canonicalServerId = CanonicalServerId(canonicalServerId),
            uri = "file:///workspace/$path",
            path = path,
            startLine = 1,
            endLine = 2,
        )

    private data class DetailFixture(
        val project: Project,
        val iteration: Iteration,
        val document: DocumentSnapshot,
        val taskGraph: TaskGraph,
        val task: Task,
        val run: RunRecord,
        val proposal: DocumentSnapshot,
    )

    private companion object {
        val pgvectorImage: DockerImageName = DockerImageName
            .parse("pgvector/pgvector:0.8.5-pg17-bookworm@sha256:d2ef61f42ef767baa5a1475393303cc235bcd92febd9d7014eddb48b41f3bad0")
            .asCompatibleSubstituteFor("postgres")

        @JvmStatic
        val postgres: PgVectorContainer = PgVectorContainer(pgvectorImage)
            .withDatabaseName("p2a_dashboard_read_test")
            .withUsername("p2a")
            .withPassword("p2a")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            if (!postgres.isRunning) {
                postgres.start()
            }
            waitUntilJdbcReachable()
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }

        @AfterAll
        @JvmStatic
        fun stopPostgres() {
            postgres.stop()
        }

        private fun waitUntilJdbcReachable() {
            val deadline = System.nanoTime() + 30_000_000_000L
            var lastFailure: Exception? = null
            while (System.nanoTime() < deadline) {
                try {
                    DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { return }
                } catch (failure: Exception) {
                    lastFailure = failure
                    Thread.sleep(200)
                }
            }
            throw IllegalStateException("PostgreSQL Testcontainer JDBC URL was not reachable", lastFailure)
        }
    }
}
