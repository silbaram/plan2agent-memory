package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.github.silbaram.plan2agent.memory.application.port.out.DashboardReadPort
import com.github.silbaram.plan2agent.memory.application.port.out.IterationStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ProjectStorePort
import com.github.silbaram.plan2agent.memory.application.usecase.IterationSummaryPageQuery
import com.github.silbaram.plan2agent.memory.application.usecase.MAX_DASHBOARD_PAGE_LIMIT
import com.github.silbaram.plan2agent.memory.application.usecase.ProjectSummaryPageQuery
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.Iteration
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.Project
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
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
