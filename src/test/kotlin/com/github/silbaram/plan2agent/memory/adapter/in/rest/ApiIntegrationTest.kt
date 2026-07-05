@file:Suppress("DEPRECATION")

package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get as getRequest
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post as postRequest
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

@SpringBootTest(
    properties = [
        "p2a.security.token=local-api-test-token",
    ],
)
class ApiIntegrationTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        jdbc.execute(
            """
            TRUNCATE TABLE
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
    fun `full authenticated API sync flow is idempotent and exposes sync metadata`() {
        val fixture = ApiFixture("api-sync")
        val projectBody = fixture.projectBody()
        val iterationBody = fixture.iterationBody()
        val documentBody = fixture.documentBody()
        val taskGraphBody = fixture.taskGraphBody()
        val tasksBody = fixture.tasksBody()
        val runBody = fixture.runBody()
        val chunksBody = fixture.chunksBody()

        val project = postJson("/api/projects", projectBody).expectCreatedJson()
        postJson("/api/projects", projectBody).expectCreatedJson()
        assertThat(project["projectId"].asText()).isEqualTo(fixture.projectId)
        assertThat(project["canonicalServerId"].asText()).isEqualTo(fixture.projectId)
        assertThat(project["sourceProjectId"].asText()).isEqualTo(fixture.sourceProjectId)
        assertThat(project["sourceReference"]["canonicalServerId"].asText()).isEqualTo(fixture.projectId)

        val iteration = postJson("/api/projects/${fixture.projectId}/iterations", iterationBody).expectCreatedJson()
        postJson("/api/projects/${fixture.projectId}/iterations", iterationBody).expectCreatedJson()
        assertThat(iteration["projectId"].asText()).isEqualTo(fixture.projectId)
        assertThat(iteration["sourceIterationId"].asText()).isEqualTo(fixture.sourceIterationId)

        val document = postJson("/api/documents/snapshots", documentBody).expectCreatedJson()
        val repeatedDocument = postJson("/api/documents/snapshots", documentBody).expectCreatedJson()
        assertThat(repeatedDocument["documentId"].asText()).isEqualTo(fixture.documentId)
        assertThat(document["lineage"]["contentHash"].asText()).isEqualTo(fixture.documentHash)
        assertThat(document["lineage"]["snapshotVersion"].asInt()).isEqualTo(1)
        assertThat(document["metadata"]["sourceDocumentId"].asText()).isEqualTo(fixture.sourceDocumentId)
        assertThat(document["sourceReference"]["path"].asText()).isEqualTo(fixture.sourcePath)

        val taskGraph = postJson("/api/task-graphs", taskGraphBody).expectCreatedJson()
        postJson("/api/task-graphs", taskGraphBody).expectCreatedJson()
        assertThat(taskGraph["metadata"]["sourceTaskGraphId"].asText()).isEqualTo(fixture.sourceTaskGraphId)
        assertThat(taskGraph["lineage"]["contentHash"].asText()).isEqualTo(fixture.graphHash)

        val tasks = postJson("/api/tasks/bulk", tasksBody).expectCreatedJson()
        postJson("/api/tasks/bulk", tasksBody).expectCreatedJson()
        assertThat(tasks.single()["metadata"]["sourceTaskId"].asText()).isEqualTo(fixture.sourceTaskId)
        assertThat(tasks.single()["lineage"]["taskId"].asText()).isEqualTo(fixture.taskId)

        val run = postJson("/api/runs", runBody).expectCreatedJson()
        postJson("/api/runs", runBody).expectCreatedJson()
        assertThat(run["metadata"]["sourceRunId"].asText()).isEqualTo(fixture.sourceRunId)
        assertThat(run["lineage"]["runId"].asText()).isEqualTo(fixture.runId)

        val chunks = postJson("/api/document-chunks/bulk", chunksBody).expectCreatedJson()
        val repeatedChunks = postJson("/api/document-chunks/bulk", chunksBody).expectCreatedJson()
        assertThat(chunks.single()["chunkId"].asText()).isEqualTo(fixture.chunkId)
        assertThat(repeatedChunks.single()["chunkId"].asText()).isEqualTo(fixture.chunkId)
        assertThat(chunks.single()["lineage"]["taskId"].asText()).isEqualTo(fixture.taskId)
        assertThat(chunks.single()["lineage"]["runId"].asText()).isEqualTo(fixture.runId)
        assertThat(chunks.single()["sourceReference"]["path"].asText()).isEqualTo("${fixture.sourcePath}#chunk-0")

        assertThat(rowCount("projects")).isEqualTo(1)
        assertThat(rowCount("iterations")).isEqualTo(1)
        assertThat(rowCount("documents")).isEqualTo(1)
        assertThat(rowCount("task_graphs")).isEqualTo(1)
        assertThat(rowCount("tasks")).isEqualTo(1)
        assertThat(rowCount("runs")).isEqualTo(1)
        assertThat(rowCount("document_chunks")).isEqualTo(1)
        assertThat(rowCount("embedding_sets")).isEqualTo(1)
        assertThat(rowCount("chunk_embeddings")).isEqualTo(1)

        val artifactLookup = getJson(
            "/api/artifacts" +
                "?projectId=${fixture.projectId}" +
                "&iterationId=${fixture.iterationId}" +
                "&artifactType=DOCUMENT_CHUNK" +
                "&sourcePath=${fixture.sourcePath}" +
                "&taskId=${fixture.taskId}" +
                "&runId=${fixture.runId}",
        ).andExpect(status().isOk()).andReturnJson()
        assertThat(artifactLookup.single()["artifactId"].asText()).isEqualTo(fixture.chunkId)
        assertThat(artifactLookup.single()["lineage"]["runId"].asText()).isEqualTo(fixture.runId)
        assertThat(artifactLookup.single()["sourceIds"]["sourceDocumentId"].asText()).isEqualTo(fixture.sourceDocumentId)
        assertThat(artifactLookup.single()["sourceIds"]["sourceRunId"].asText()).isEqualTo(fixture.sourceRunId)

        val keywordResults = getJson(
            "/api/search/keyword" +
                "?q=api-search-needle" +
                "&projectId=${fixture.projectId}" +
                "&iterationId=${fixture.iterationId}" +
                "&taskId=${fixture.taskId}" +
                "&runId=${fixture.runId}" +
                "&limit=5",
        ).andExpect(status().isOk()).andReturnJson()
        assertThat(keywordResults.single()["chunkId"].asText()).isEqualTo(fixture.chunkId)
        assertThat(keywordResults.single()["matchReason"].asText()).isEqualTo("chunk.content")
        assertThat(keywordResults.single()["score"].asDouble()).isGreaterThanOrEqualTo(0.0)
        assertThat(keywordResults.single()["sourceIds"]["sourceTaskId"].asText()).isEqualTo(fixture.sourceTaskId)

        val vectorResults = postJson(
            "/api/search/vector",
            mapOf(
                "embedding" to listOf(1.0f, 0.0f),
                "embeddingModel" to fixture.embeddingModel,
                "embeddingDimension" to 2,
                "embeddingVersion" to fixture.embeddingVersion,
                "distanceMetric" to "COSINE",
                "projectId" to fixture.projectId,
                "iterationId" to fixture.iterationId,
                "taskId" to fixture.taskId,
                "runId" to fixture.runId,
                "metadataFilters" to emptyMap<String, String>(),
                "limit" to 5,
            ),
        ).expectOkJson()
        assertThat(vectorResults.single()["chunkId"].asText()).isEqualTo(fixture.chunkId)
        assertThat(vectorResults.single()["distanceMetric"].asText()).isEqualTo("COSINE")
        assertThat(vectorResults.single()["embeddingModel"].asText()).isEqualTo(fixture.embeddingModel)
        assertThat(vectorResults.single()["sourceIds"]["sourceRunId"].asText()).isEqualTo(fixture.sourceRunId)
    }

    @Test
    fun `proposal snapshots can be stored and searched as first class artifacts`() {
        val fixture = ApiFixture("proposal-sync")
        postJson("/api/projects", fixture.projectBody()).andExpect(status().isCreated())
        postJson("/api/projects/${fixture.projectId}/iterations", fixture.iterationBody()).andExpect(status().isCreated())

        val proposalId = uuid("proposal-sync-proposal")
        val proposalPath = ".plan2agent/proposals/proposal-run-123-harness-gap.json"
        val proposalContent = """
            {
              "schema_version": "p2a.skill_proposal.v1",
              "proposalId": "proposal-run-123-harness-gap",
              "target": "p2a_toolkit",
              "targetRepo": "https://github.com/silbaram/plan2agent",
              "problem": "P2A toolkit proposal routing should preserve upstream evidence.",
              "recommendedChange": "Import upstream proposals into the Plan2Agent toolkit queue."
            }
        """.trimIndent()
        val proposalBody = mapOf(
            "documentId" to proposalId,
            "projectId" to fixture.projectId,
            "iterationId" to fixture.iterationId,
            "sourceDocumentId" to "proposal-run-123-harness-gap",
            "sourcePath" to proposalPath,
            "snapshotVersion" to 1,
            "artifactType" to "PROPOSAL",
            "title" to "Proposal: harness gap",
            "content" to proposalContent,
            "contentHash" to "proposal-sync-hash",
            "sourceReference" to sourceReference(proposalId, proposalPath),
            "capturedAt" to NOW,
            "createdAt" to NOW,
            "metadata" to mapOf(
                "proposalTarget" to "p2a_toolkit",
                "targetRepo" to "https://github.com/silbaram/plan2agent",
                "proposalId" to "proposal-run-123-harness-gap",
            ),
        )

        val proposal = postJson("/api/documents/snapshots", proposalBody).expectCreatedJson()
        assertThat(proposal["artifactType"].asText()).isEqualTo("PROPOSAL")
        assertThat(proposal["metadata"]["proposalTarget"].asText()).isEqualTo("p2a_toolkit")

        val artifacts = getJson(
            "/api/artifacts" +
                "?projectId=${fixture.projectId}" +
                "&iterationId=${fixture.iterationId}" +
                "&artifactType=PROPOSAL" +
                "&sourcePath=$proposalPath",
        ).andExpect(status().isOk()).andReturnJson()
        assertThat(artifacts.single()["artifactType"].asText()).isEqualTo("PROPOSAL")
        assertThat(artifacts.single()["sourceIds"]["sourceDocumentId"].asText()).isEqualTo("proposal-run-123-harness-gap")
        assertThat(artifacts.single()["metadata"]["proposalTarget"].asText()).isEqualTo("p2a_toolkit")

        val search = getJson(
            "/api/search/keyword" +
                "?q=p2a_toolkit" +
                "&projectId=${fixture.projectId}" +
                "&iterationId=${fixture.iterationId}" +
                "&artifactType=PROPOSAL" +
                "&limit=5",
        ).andExpect(status().isOk()).andReturnJson()
        assertThat(search.single()["documentId"].asText()).isEqualTo(proposalId)
        assertThat(search.single()["artifactType"].asText()).isEqualTo("PROPOSAL")
        assertThat(search.single()["metadata"]["proposalTarget"].asText()).isEqualTo("p2a_toolkit")
    }

    @Test
    fun `API returns auth validation not found and conflict errors`() {
        getWithoutToken("/api/artifacts").andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("auth_error"))

        postJson(
            "/api/projects",
            mapOf(
                "projectId" to uuid("invalid-project"),
                "sourceProjectId" to "source-invalid-project",
                "rootPath" to "/repo/invalid",
                "metadata" to emptyMap<String, String>(),
            ),
        ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation_error"))
            .andExpect(jsonPath("$.message").value("name is required"))

        val missingProjectFixture = ApiFixture("missing-project")
        postJson("/api/documents/snapshots", missingProjectFixture.documentBody())
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"))

        val conflictFixture = ApiFixture("api-conflict")
        saveSyncFixture(conflictFixture)
        postJson(
            "/api/runs",
            conflictFixture.runBody(runId = uuid("api-conflict-other-run")),
        ).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("conflict"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already maps")))
    }

    @Test
    fun `compose smoke path is configured and actuator health reports database connectivity`() {
        val compose = Path.of("compose.yaml").toFile().readText()
        val application = Path.of("src/main/resources/application.yml").toFile().readText()

        assertThat(compose).contains(
            "pgvector/pgvector",
            "POSTGRES_DB: p2a_artifact_store",
            "POSTGRES_USER: p2a",
            "POSTGRES_PASSWORD: p2a_local_password",
            "\"5432:5432\"",
            "pg_isready -U p2a -d p2a_artifact_store",
        )
        assertThat(application).contains(
            "jdbc:postgresql://localhost:5432/p2a_artifact_store",
            "username: ${'$'}{P2A_DB_USERNAME:p2a}",
            "password: ${'$'}{P2A_DB_PASSWORD:p2a_local_password}",
            "/actuator/health",
        )

        getWithoutToken("/api/health")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))

        getWithoutToken("/actuator/health")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
    }

    private fun saveSyncFixture(fixture: ApiFixture) {
        postJson("/api/projects", fixture.projectBody()).andExpect(status().isCreated())
        postJson("/api/projects/${fixture.projectId}/iterations", fixture.iterationBody()).andExpect(status().isCreated())
        postJson("/api/documents/snapshots", fixture.documentBody()).andExpect(status().isCreated())
        postJson("/api/task-graphs", fixture.taskGraphBody()).andExpect(status().isCreated())
        postJson("/api/tasks/bulk", fixture.tasksBody()).andExpect(status().isCreated())
        postJson("/api/runs", fixture.runBody()).andExpect(status().isCreated())
        postJson("/api/document-chunks/bulk", fixture.chunksBody()).andExpect(status().isCreated())
    }

    private fun getWithoutToken(path: String) =
        mockMvc.perform(getRequest(path).accept(MediaType.APPLICATION_JSON))

    private fun getJson(path: String) =
        mockMvc.perform(
            getRequest(path)
                .header(LOCAL_TOKEN_HEADER, LOCAL_TOKEN)
                .accept(MediaType.APPLICATION_JSON),
        )

    private fun postJson(path: String, body: Any) =
        mockMvc.perform(
            postRequest(path)
                .header(LOCAL_TOKEN_HEADER, LOCAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        )

    private fun org.springframework.test.web.servlet.ResultActions.expectCreatedJson(): JsonNode =
        andExpect(status().isCreated()).andReturnJson()

    private fun org.springframework.test.web.servlet.ResultActions.expectOkJson(): JsonNode =
        andExpect(status().isOk()).andReturnJson()

    private fun org.springframework.test.web.servlet.ResultActions.andReturnJson(): JsonNode =
        objectMapper.readTree(andReturn().response.contentAsString)

    private fun JsonNode.single(): JsonNode {
        assertThat(isArray).isTrue()
        assertThat(size()).isEqualTo(1)
        return this[0]
    }

    private fun rowCount(table: String): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java) ?: 0L

    companion object {
        private const val LOCAL_TOKEN_HEADER = "X-P2A-Local-Token"
        private const val LOCAL_TOKEN = "local-api-test-token"

        private val pgvectorImage = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres")

        @JvmStatic
        val postgres: ApiPgVectorContainer = ApiPgVectorContainer(pgvectorImage)
            .withDatabaseName("p2a_memory_api_test")
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

class ApiPgVectorContainer(imageName: DockerImageName) : PostgreSQLContainer<ApiPgVectorContainer>(imageName)

private data class ApiFixture(
    val scope: String,
) {
    val projectId: String = uuid("$scope-project")
    val iterationId: String = uuid("$scope-iteration")
    val documentId: String = uuid("$scope-document")
    val taskGraphId: String = uuid("$scope-task-graph")
    val taskId: String = uuid("$scope-task")
    val runId: String = uuid("$scope-run")
    val chunkId: String = uuid("$scope-chunk")
    val embeddingSetId: String = uuid("$scope-embedding-set")
    val sourceProjectId: String = "source-project-$scope"
    val sourceIterationId: String = "source-iteration-$scope"
    val sourceDocumentId: String = "source-document-$scope"
    val sourceTaskGraphId: String = "source-task-graph-$scope"
    val sourceTaskId: String = "source-task-$scope"
    val sourceRunId: String = "source-run-$scope"
    val sourcePath: String = "docs/$scope.md"
    val documentHash: String = "document-hash-$scope"
    val graphHash: String = "task-graph-hash-$scope"
    val chunkHash: String = "chunk-hash-$scope"
    val embeddingHash: String = "embedding-hash-$scope"
    val embeddingModel: String = "text-embedding-api"
    val embeddingVersion: String = "v1"

    fun projectBody(): Map<String, Any?> =
        mapOf(
            "projectId" to projectId,
            "sourceProjectId" to sourceProjectId,
            "name" to "Project $scope",
            "canonicalServerId" to projectId,
            "rootPath" to "/repo/$scope",
            "sourceReference" to sourceReference(projectId, "projects/$scope"),
            "createdAt" to NOW,
            "metadata" to mapOf("workspace" to scope),
        )

    fun iterationBody(): Map<String, Any?> =
        mapOf(
            "iterationId" to iterationId,
            "sourceIterationId" to sourceIterationId,
            "label" to "Iteration $scope",
            "status" to "ACTIVE",
            "sourceReference" to sourceReference(iterationId, "iterations/$scope"),
            "createdAt" to NOW,
            "metadata" to mapOf("gate" to "v1"),
        )

    fun documentBody(): Map<String, Any?> =
        mapOf(
            "documentId" to documentId,
            "projectId" to projectId,
            "iterationId" to iterationId,
            "sourceDocumentId" to sourceDocumentId,
            "sourcePath" to sourcePath,
            "snapshotVersion" to 1,
            "artifactType" to "DOCUMENT_SNAPSHOT",
            "title" to "Document $scope",
            "content" to "Document content for $scope with API integration context.",
            "contentHash" to documentHash,
            "sourceReference" to sourceReference(documentId, sourcePath),
            "capturedAt" to NOW,
            "createdAt" to NOW,
            "metadata" to mapOf("kind" to "spec"),
        )

    fun taskGraphBody(): Map<String, Any?> =
        mapOf(
            "taskGraphId" to taskGraphId,
            "projectId" to projectId,
            "iterationId" to iterationId,
            "sourceTaskGraphId" to sourceTaskGraphId,
            "sourceDocumentId" to sourceDocumentId,
            "graphHash" to graphHash,
            "graphJson" to """{"tasks":["$taskId"]}""",
            "taskIds" to listOf(taskId),
            "dependencyEdges" to emptyList<Map<String, String>>(),
            "sourceReference" to sourceReference(taskGraphId, "task-graphs/$scope.json"),
            "createdAt" to NOW,
            "metadata" to mapOf("kind" to "task-graph"),
        )

    fun tasksBody(): Map<String, Any?> =
        mapOf(
            "graphId" to taskGraphId,
            "tasks" to listOf(
                mapOf(
                    "taskId" to taskId,
                    "projectId" to projectId,
                    "iterationId" to iterationId,
                    "taskGraphId" to taskGraphId,
                    "sourceTaskId" to sourceTaskId,
                    "title" to "Task $scope",
                    "description" to "Task description for $scope",
                    "status" to "READY",
                    "targetArea" to "api-tests",
                    "dependencies" to emptyList<String>(),
                    "acceptanceCriteria" to listOf("Exercise API sync flow"),
                    "sourceReference" to sourceReference(taskId, "task-graphs/$scope.json#task"),
                    "createdAt" to NOW,
                    "metadata" to mapOf("priority" to "high"),
                ),
            ),
        )

    fun runBody(runId: String = this.runId): Map<String, Any?> =
        mapOf(
            "runId" to runId,
            "projectId" to projectId,
            "iterationId" to iterationId,
            "taskId" to taskId,
            "sourceRunId" to sourceRunId,
            "status" to "FINISHED",
            "agentTool" to "codex",
            "runJson" to """{"status":"finished"}""",
            "artifactRefs" to listOf(
                mapOf(
                    "artifactType" to "DOCUMENT_SNAPSHOT",
                    "artifactId" to documentId,
                    "sourcePath" to sourcePath,
                ),
            ),
            "sourceReference" to sourceReference(runId, "runs/$scope.json"),
            "startedAt" to NOW,
            "finishedAt" to NOW,
            "createdAt" to NOW,
            "metadata" to mapOf("agent" to "codex"),
        )

    fun chunksBody(): Map<String, Any?> =
        mapOf(
            "documentId" to documentId,
            "chunks" to listOf(
                mapOf(
                    "chunk" to mapOf(
                        "chunkId" to chunkId,
                        "projectId" to projectId,
                        "iterationId" to iterationId,
                        "taskId" to taskId,
                        "runId" to runId,
                        "artifactType" to "DOCUMENT_SNAPSHOT",
                        "sourcePath" to sourcePath,
                        "chunkIndex" to 0,
                        "content" to "Chunk content for $scope includes api-search-needle and vector evidence.",
                        "chunkHash" to chunkHash,
                        "tokenEstimate" to 10,
                        "sourceReference" to sourceReference(chunkId, "$sourcePath#chunk-0"),
                        "createdAt" to NOW,
                        "metadata" to mapOf("phase" to "gate-e", "kind" to "chunk"),
                    ),
                    "embeddingSet" to mapOf(
                        "embeddingSetId" to embeddingSetId,
                        "projectId" to projectId,
                        "embeddingModel" to embeddingModel,
                        "embeddingDimension" to 2,
                        "embeddingVersion" to embeddingVersion,
                        "distanceMetric" to "COSINE",
                        "storageType" to "VECTOR_INDEX",
                        "createdAt" to NOW,
                        "metadata" to emptyMap<String, String>(),
                    ),
                    "embedding" to listOf(1.0f, 0.0f),
                    "embeddingHash" to embeddingHash,
                ),
            ),
        )
}

private fun sourceReference(canonicalId: String, path: String): Map<String, Any?> =
    mapOf(
        "canonicalServerId" to canonicalId,
        "uri" to "file:///repo/$path",
        "path" to path,
    )

private fun uuid(seed: String): String =
    UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()

private const val NOW = "2026-06-29T00:00:00Z"
