package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.silbaram.plan2agent.memory.Plan2AgentMemoryApplication
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingBackfillStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolver
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.ChunkEmbeddingStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentChunkStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingJobStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingSetStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.StructurallyValidPersistedActiveEmbeddingSetResolver
import com.github.silbaram.plan2agent.memory.application.worker.EmbeddingJobCompletionService
import com.github.silbaram.plan2agent.memory.application.worker.EmbeddingJobWorker
import com.github.silbaram.plan2agent.memory.application.worker.EmbeddingWorkerProperties
import com.github.silbaram.plan2agent.memory.domain.ChunkEmbedding
import com.github.silbaram.plan2agent.memory.domain.ChunkEmbeddingId
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobStatus
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSet
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingStorageType
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import com.github.silbaram.plan2agent.memory.support.FakeEmbeddingFailure
import com.github.silbaram.plan2agent.memory.support.FakeEmbeddingMode
import com.github.silbaram.plan2agent.memory.support.FakeEmbeddingPort
import com.github.silbaram.plan2agent.memory.support.KoreanRetrievalRankingFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regression boundary for the fixed V2 profile. This deliberately uses the deterministic fake
 * provider so it starts no model, downloads no artifacts, and needs no provider credentials.
 */
@SpringBootTest(
    classes = [Plan2AgentMemoryApplication::class],
    properties = [
        "p2a.security.token=fake-provider-v2-test-token",
        "p2a.embedding.provider=none",
        "p2a.memory.embedding.worker.enabled=false",
        "p2a.memory.scheduling.enabled=false",
    ],
)
@Import(FakeProviderV2EndToEndIntegrationTest.FakeProviderConfiguration::class)
class FakeProviderV2EndToEndIntegrationTest {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var fakeEmbeddingPort: FakeEmbeddingPort

    @Autowired
    private lateinit var activeEmbeddingProfileResolver: ActiveEmbeddingProfileResolver

    @Autowired
    private lateinit var structurallyValidActiveEmbeddingSetResolver: StructurallyValidPersistedActiveEmbeddingSetResolver

    @Autowired
    private lateinit var documentChunkStore: DocumentChunkStorePort

    @Autowired
    private lateinit var embeddingJobStore: EmbeddingJobStorePort

    @Autowired
    private lateinit var completionService: EmbeddingJobCompletionService

    @Autowired
    private lateinit var activeEmbeddingBackfillStore: ActiveEmbeddingBackfillStorePort

    @Autowired
    private lateinit var embeddingSetStore: EmbeddingSetStorePort

    @Autowired
    private lateinit var chunkEmbeddingStore: ChunkEmbeddingStorePort

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        fakeEmbeddingPort.providerState = com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState.READY
        FakeEmbeddingMode.entries.forEach(fakeEmbeddingPort::clearFailure)
        // Do not TRUNCATE projects with CASCADE here: PostgreSQL would truncate the whole
        // embedding_sets table through its legacy project foreign key, including the immutable
        // server-global V2 target that the fake provider was bound to during context startup.
        listOf(
            "DELETE FROM artifact_edges",
            "DELETE FROM artifact_nodes",
            "DELETE FROM embedding_jobs",
            "DELETE FROM chunk_embeddings",
            "DELETE FROM document_chunks",
            "DELETE FROM runs",
            "DELETE FROM tasks",
            "DELETE FROM task_graphs",
            "DELETE FROM documents",
            "DELETE FROM iterations",
            "DELETE FROM projects",
        ).forEach(jdbc::execute)
    }

    @Test
    fun `offline fake provider drives fixed profile write queue Korean retrieval and public contract regressions`() {
        val fixture = V2Fixture("fake-v2-e2e")
        val ranking = koreanRankingFixture()
        ranking.installInto(fakeEmbeddingPort)

        val activeSetId = requireNotNull(fakeEmbeddingPort.activeEmbeddingTarget.embeddingSetId)
        assertThat(activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId()).isEqualTo(activeSetId)
        assertThat(structurallyValidActiveEmbeddingSetResolver.findStructurallyValidActiveV2EmbeddingSetId())
            .isEqualTo(activeSetId)
        assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM embedding_sets
                WHERE embedding_set_id = ?
                  AND scope = 'SERVER_GLOBAL'
                  AND profile_fingerprint = ?
                  AND embedding_dimension = 384
                """.trimIndent(),
                Long::class.java,
                UUID.fromString(activeSetId.value),
                V2EmbeddingProfile.fixed.fingerprint,
            ),
        ).isEqualTo(1L)

        saveRestFixture(fixture, ranking.documentsInExpectedRankOrder)
        saveRestFixture(fixture, ranking.documentsInExpectedRankOrder)
        assertThat(rowCount("document_chunks")).isEqualTo(2L)
        assertThat(rowCount("embedding_jobs")).isEqualTo(2L)

        makeEmbeddingJobsDue()
        runWorker(fakeEmbeddingPort)
        assertThat(jobStatuses()).containsOnly(EmbeddingJobStatus.SUCCEEDED.name.lowercase())
        assertThat(rowCount("chunk_embeddings")).isEqualTo(2L)
        assertThat(rowCount("chunk_embedding_vectors_384")).isEqualTo(2L)

        val semanticRequest = fixture.semanticRequest(ranking.query, limit = 1)
        val firstSemantic = postJson("/api/search/semantic", semanticRequest).expectOkJson()
        assertThat(firstSemantic["items"].single()["chunkId"].asText()).isEqualTo(fixture.chunkIds.first())
        assertCitation(firstSemantic["items"].single(), fixture)
        val semanticCursor = firstSemantic["nextCursor"].asText()
        assertThat(semanticCursor).isNotBlank()

        val secondSemantic = postJson(
            "/api/search/semantic",
            semanticRequest + mapOf("cursor" to semanticCursor),
        ).expectOkJson()
        assertThat(secondSemantic["items"].single()["chunkId"].asText()).isEqualTo(fixture.chunkIds.last())
        assertThat(secondSemantic["nextCursor"].isNull).isTrue()
        postJson(
            "/api/search/semantic",
            semanticRequest + mapOf("cursor" to semanticCursor, "sourcePath" to "docs/other.md"),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation_error"))

        val hybrid = postJson("/api/search/hybrid", fixture.hybridRequest(ranking.query)).expectOkJson()
        assertThat(hybrid["items"].single()["chunkId"].asText()).isEqualTo(fixture.chunkIds.first())
        assertThat(hybrid["items"].single()["keyword"]).isNotNull
        assertThat(hybrid["items"].single()["vector"]).isNotNull
        assertCitation(hybrid["items"].single(), fixture)

        val keywordFirstPage = getJson(fixture.keywordUrl("policy", limit = 1)).expectOkJson()
        assertThat(keywordFirstPage["items"].single()["matchReason"].asText()).isEqualTo("chunk.content")
        assertCitation(keywordFirstPage["items"].single(), fixture)
        val keywordCursor = keywordFirstPage["nextCursor"].asText()
        assertThat(keywordCursor).isNotBlank()
        val keywordSecondPage = getJson(fixture.keywordUrl("policy", limit = 1, cursor = keywordCursor)).expectOkJson()
        assertThat(keywordSecondPage["items"].single()["chunkId"].asText())
            .isNotEqualTo(keywordFirstPage["items"].single()["chunkId"].asText())

        val artifacts = getJson(fixture.artifactUrl(limit = 1)).expectOkJson()
        assertThat(artifacts["items"].single()["artifactId"].asText()).isEqualTo(fixture.chunkIds.first())
        assertThat(artifacts["items"].single()["sourceIds"]["sourceTaskId"].asText()).isEqualTo(fixture.sourceTaskId)
        assertThat(artifacts["nextCursor"].asText()).isNotBlank()
        val artifactSecondPage = getJson(fixture.artifactUrl(limit = 1, cursor = artifacts["nextCursor"].asText())).expectOkJson()
        assertThat(artifactSecondPage["items"].single()["artifactId"].asText())
            .isNotEqualTo(artifacts["items"].single()["artifactId"].asText())

        val graph = postJson("/api/graph/snapshots", fixture.graphSnapshotBody()).expectCreatedJson()
        assertThat(graph["nodeCount"].asInt()).isEqualTo(2)
        val trace = getJson("/api/graph/trace?projectId=${fixture.projectId}&naturalKey=task:${fixture.sourceTaskId}")
            .expectOkJson()
        assertThat(trace["nodes"].size()).isGreaterThanOrEqualTo(1)

        postJson("/api/search/vector", mapOf("embedding" to listOf(1.0f))).andExpect(status().isNotFound())
        postJson("/api/embedding-profiles/active", emptyMap<String, String>()).andExpect(status().isNotFound())
        postJson(
            "/api/document-chunks/bulk",
            mapOf("documentId" to fixture.documentId, "chunks" to listOf(mapOf("embedding" to listOf(1.0f)))),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation_error"))
    }

    @Test
    fun `fake provider keeps both provider failures explicit and worker recovery fenced with active backfill repair`() {
        val fixture = V2Fixture("fake-v2-worker")
        val ranking = koreanRankingFixture()
        ranking.installInto(fakeEmbeddingPort)
        saveRestFixture(fixture, ranking.documentsInExpectedRankOrder)
        makeEmbeddingJobsDue()

        fakeEmbeddingPort.providerState = com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState.NOT_CONFIGURED
        postJson("/api/search/semantic", fixture.semanticRequest(ranking.query, limit = 1))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("embedding_provider_not_configured"))
        fakeEmbeddingPort.providerState = com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState.UNAVAILABLE
        postJson("/api/search/hybrid", fixture.hybridRequest(ranking.query))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("embedding_provider_unavailable"))
        fakeEmbeddingPort.providerState = com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState.READY

        fakeEmbeddingPort.fail(FakeEmbeddingMode.DOCUMENT, FakeEmbeddingFailure.RETRYABLE_UNAVAILABLE)
        runWorker(
            fakeEmbeddingPort,
            EmbeddingWorkerProperties(
                maxAttempts = 3,
                initialRetryDelay = Duration.ofSeconds(1),
                maxRetryDelay = Duration.ofSeconds(2),
            ),
        )
        assertThat(jobStatuses()).containsOnly(EmbeddingJobStatus.RETRYING.name.lowercase())
        jdbc.update("UPDATE embedding_jobs SET next_attempt_at = now() - INTERVAL '1 second'")
        fakeEmbeddingPort.clearFailure(FakeEmbeddingMode.DOCUMENT)
        runWorker(fakeEmbeddingPort)
        assertThat(jobStatuses()).containsOnly(EmbeddingJobStatus.SUCCEEDED.name.lowercase())

        val activeSetId = requireNotNull(fakeEmbeddingPort.activeEmbeddingTarget.embeddingSetId)
        val jobId = jdbc.queryForObject(
            "SELECT embedding_job_id::text FROM embedding_jobs ORDER BY embedding_job_id LIMIT 1",
            String::class.java,
        )!!
        jdbc.update(
            """
            UPDATE embedding_jobs
            SET status = 'pending', attempt_count = 0, lease_owner = NULL, lease_expires_at = NULL,
                completed_at = NULL, next_attempt_at = now() - INTERVAL '1 second'
            WHERE embedding_job_id = ?
            """.trimIndent(),
            UUID.fromString(jobId),
        )
        val crashedClaim = embeddingJobStore.claimDueForEmbeddingSet(
            activeSetId,
            1,
            "crashed-fake-worker",
            Instant.now().minusSeconds(1),
        ).single()
        runWorker(fakeEmbeddingPort)
        assertThat(embeddingJobStore.markSucceeded(crashedClaim.id, "crashed-fake-worker", crashedClaim.leaseGeneration)).isNull()

        jdbc.update("DELETE FROM chunk_embedding_vectors_384")
        val repaired = activeEmbeddingBackfillStore.reconcile(activeSetId, 50, null, Instant.now())
        assertThat(repaired.repairedTypedMirrors).isEqualTo(2)
        assertThat(repaired.enqueuedJobs).isZero()
        assertThat(rowCount("chunk_embedding_vectors_384")).isEqualTo(2L)

        val legacySet = embeddingSetStore.resolveOrCreate(
            EmbeddingSet(
                id = EmbeddingSetId(stableUuid("${fixture.scope}-legacy-set")),
                projectId = ProjectId(fixture.projectId),
                embeddingModel = "legacy-v1-${fixture.scope}",
                embeddingDimension = V2EmbeddingProfile.fixed.dimension,
                embeddingVersion = "v1",
                distanceMetric = DistanceMetric.COSINE,
                storageType = EmbeddingStorageType.VECTOR_INDEX,
                createdAt = Instant.parse("2026-07-24T00:00:00Z"),
            ),
        )
        chunkEmbeddingStore.saveAll(
            listOf(
                ChunkEmbedding(
                    id = ChunkEmbeddingId(stableUuid("${fixture.scope}-legacy-vector")),
                    embeddingSetId = legacySet.id,
                    chunkId = DocumentChunkId(fixture.chunkIds.first()),
                    embedding = Embedding(oneHotEmbedding()),
                    createdAt = Instant.parse("2026-07-24T00:00:00Z"),
                ),
            ),
        )
        assertThat(rowCount("chunk_embeddings")).isEqualTo(3L)
        assertThat(
            postJson("/api/search/semantic", fixture.semanticRequest(ranking.query, limit = 10))
                .expectOkJson()["items"].map { it["chunkId"].asText() },
        ).containsExactlyElementsOf(fixture.chunkIds)
    }

    private fun saveRestFixture(fixture: V2Fixture, contents: List<String>) {
        postJson("/api/projects", fixture.projectBody()).expectCreatedJson()
        postJson("/api/projects/${fixture.projectId}/iterations", fixture.iterationBody()).expectCreatedJson()
        postJson("/api/documents/snapshots", fixture.documentBody()).expectCreatedJson()
        postJson("/api/task-graphs", fixture.taskGraphBody()).expectCreatedJson()
        postJson("/api/tasks/bulk", fixture.tasksBody()).expectCreatedJson()
        postJson("/api/runs", fixture.runBody()).expectCreatedJson()
        postJson("/api/document-chunks/bulk", fixture.chunksBody(contents)).expectCreatedJson()
    }

    private fun runWorker(
        fake: FakeEmbeddingPort,
        properties: EmbeddingWorkerProperties = EmbeddingWorkerProperties(),
        clock: Clock = Clock.systemUTC(),
    ) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            EmbeddingJobWorker(
                embeddingPort = fake,
                activeEmbeddingProfileResolver = activeEmbeddingProfileResolver,
                documentChunkStore = documentChunkStore,
                embeddingJobStore = embeddingJobStore,
                completionService = completionService,
                properties = properties,
                workerExecutor = executor,
                clock = clock,
            ).poll()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private fun assertCitation(result: JsonNode, fixture: V2Fixture) {
        assertThat(result["lineage"]["projectId"].asText()).isEqualTo(fixture.projectId)
        assertThat(result["sourceIds"]["sourceProjectId"].asText()).isEqualTo(fixture.sourceProjectId)
        assertThat(result["sourceIds"]["sourceTaskId"].asText()).isEqualTo(fixture.sourceTaskId)
        assertThat(result["citation"]["sourceReference"]["path"].asText()).contains("#chunk-")
    }

    private fun jobStatuses(): List<String> =
        jdbc.query("SELECT status FROM embedding_jobs ORDER BY embedding_job_id") { row, _ -> row.getString("status") }

    private fun makeEmbeddingJobsDue() {
        jdbc.update("UPDATE embedding_jobs SET next_attempt_at = now() - INTERVAL '1 second'")
    }

    private fun rowCount(table: String): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java) ?: 0L

    private fun getJson(path: String) =
        mockMvc.perform(get(path).header(LOCAL_TOKEN_HEADER, LOCAL_TOKEN).accept(MediaType.APPLICATION_JSON))

    private fun postJson(path: String, body: Any) =
        mockMvc.perform(
            post(path)
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

    private fun oneHotEmbedding(): List<Float> = List(V2EmbeddingProfile.fixed.dimension) { index -> if (index == 0) 1f else 0f }

    private fun koreanRankingFixture(): KoreanRetrievalRankingFixture = KoreanRetrievalRankingFixture(
        documentsInExpectedRankOrder = listOf(
            "결제 취소 정책은 주문 상세 화면에서 취소 요청을 제출하는 방법을 안내하는 policy reference입니다.",
            "배송 조회 정책은 운송장 번호와 현재 배송 상태를 안내하는 policy reference입니다.",
        ),
    )

    @TestConfiguration(proxyBeanMethods = false)
    class FakeProviderConfiguration {
        @Bean
        @Primary
        fun fakeEmbeddingPort(activeEmbeddingProfileResolver: ActiveEmbeddingProfileResolver): FakeEmbeddingPort =
            FakeEmbeddingPort(
                activeEmbeddingTarget = ActiveEmbeddingTarget(
                    V2EmbeddingProfile.fixed,
                    activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId(),
                ),
            )
    }

    companion object {
        private const val LOCAL_TOKEN_HEADER = "X-P2A-Local-Token"
        private const val LOCAL_TOKEN = "fake-provider-v2-test-token"

        private val pgvectorImage = DockerImageName
            .parse("pgvector/pgvector:0.8.5-pg17-bookworm@sha256:d2ef61f42ef767baa5a1475393303cc235bcd92febd9d7014eddb48b41f3bad0")
            .asCompatibleSubstituteFor("postgres")

        @JvmStatic
        val postgres: FakeProviderPgVectorContainer = FakeProviderPgVectorContainer(pgvectorImage)
            .withDatabaseName("p2a_memory_fake_provider_v2_test")
            .withUsername("p2a")
            .withPassword("p2a")

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            if (!postgres.isRunning) {
                postgres.start()
            }
            waitUntilJdbcReachable()
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }

        @JvmStatic
        @AfterAll
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

class FakeProviderPgVectorContainer(imageName: DockerImageName) : PostgreSQLContainer<FakeProviderPgVectorContainer>(imageName)

private data class V2Fixture(
    val scope: String,
) {
    val projectId: String = stableUuid("$scope-project")
    val iterationId: String = stableUuid("$scope-iteration")
    val documentId: String = stableUuid("$scope-document")
    val taskGraphId: String = stableUuid("$scope-task-graph")
    val taskId: String = stableUuid("$scope-task")
    val runId: String = stableUuid("$scope-run")
    val chunkIds: List<String> = listOf(stableUuid("$scope-chunk-0"), stableUuid("$scope-chunk-1"))
    val sourceProjectId: String = "source-project-$scope"
    val sourceIterationId: String = "source-iteration-$scope"
    val sourceDocumentId: String = "source-document-$scope"
    val sourceTaskGraphId: String = "source-task-graph-$scope"
    val sourceTaskId: String = "source-task-$scope"
    val sourceRunId: String = "source-run-$scope"
    val sourcePath: String = "docs/$scope.md"

    fun projectBody(): Map<String, Any> = mapOf(
        "projectId" to projectId,
        "sourceProjectId" to sourceProjectId,
        "name" to "Fake provider $scope",
        "canonicalServerId" to projectId,
        "rootPath" to "/repo/$scope",
        "sourceReference" to sourceReference(projectId, "projects/$scope"),
        "createdAt" to NOW,
        "metadata" to mapOf("suite" to "fake-v2"),
    )

    fun iterationBody(): Map<String, Any> = mapOf(
        "iterationId" to iterationId,
        "sourceIterationId" to sourceIterationId,
        "label" to "Fake V2 iteration",
        "status" to "ACTIVE",
        "sourceReference" to sourceReference(iterationId, "iterations/$scope"),
        "createdAt" to NOW,
        "metadata" to mapOf("suite" to "fake-v2"),
    )

    fun documentBody(): Map<String, Any> = mapOf(
        "documentId" to documentId,
        "projectId" to projectId,
        "iterationId" to iterationId,
        "sourceDocumentId" to sourceDocumentId,
        "sourcePath" to sourcePath,
        "snapshotVersion" to 1,
        "artifactType" to "DOCUMENT_SNAPSHOT",
        "title" to "고정 V2 검색 문서",
        "content" to "결제 취소와 배송 정책을 검증하는 offline fake provider 문서입니다.",
        "contentHash" to "$scope-document-hash",
        "sourceReference" to sourceReference(documentId, sourcePath),
        "capturedAt" to NOW,
        "createdAt" to NOW,
        "metadata" to mapOf("suite" to "fake-v2"),
    )

    fun taskGraphBody(): Map<String, Any> = mapOf(
        "taskGraphId" to taskGraphId,
        "projectId" to projectId,
        "iterationId" to iterationId,
        "sourceTaskGraphId" to sourceTaskGraphId,
        "sourceDocumentId" to sourceDocumentId,
        "graphHash" to "$scope-graph-hash",
        "graphJson" to """{"tasks":["$taskId"]}""",
        "taskIds" to listOf(taskId),
        "dependencyEdges" to emptyList<Map<String, String>>(),
        "sourceReference" to sourceReference(taskGraphId, "task-graphs/$scope.json"),
        "createdAt" to NOW,
        "metadata" to mapOf("suite" to "fake-v2"),
    )

    fun tasksBody(): Map<String, Any> = mapOf(
        "graphId" to taskGraphId,
        "tasks" to listOf(
            mapOf(
                "taskId" to taskId,
                "projectId" to projectId,
                "iterationId" to iterationId,
                "taskGraphId" to taskGraphId,
                "sourceTaskId" to sourceTaskId,
                "title" to "고정 V2 fake provider task",
                "description" to "Korean retrieval regression",
                "status" to "READY",
                "targetArea" to "integration-test",
                "dependencies" to emptyList<String>(),
                "acceptanceCriteria" to listOf("offline fake provider"),
                "sourceReference" to sourceReference(taskId, "task-graphs/$scope.json#task"),
                "createdAt" to NOW,
                "metadata" to mapOf("suite" to "fake-v2"),
            ),
        ),
    )

    fun runBody(): Map<String, Any> = mapOf(
        "runId" to runId,
        "projectId" to projectId,
        "iterationId" to iterationId,
        "taskId" to taskId,
        "sourceRunId" to sourceRunId,
        "status" to "FINISHED",
        "agentTool" to "codex",
        "runJson" to """{"status":"finished"}""",
        "artifactRefs" to listOf(mapOf("artifactType" to "DOCUMENT_SNAPSHOT", "artifactId" to documentId, "sourcePath" to sourcePath)),
        "sourceReference" to sourceReference(runId, "runs/$scope.json"),
        "startedAt" to NOW,
        "finishedAt" to NOW,
        "createdAt" to NOW,
        "metadata" to mapOf("suite" to "fake-v2"),
    )

    fun chunksBody(contents: List<String>): Map<String, Any> = mapOf(
        "documentId" to documentId,
        "chunks" to contents.mapIndexed { index, content ->
            mapOf(
                "chunk" to mapOf(
                    "chunkId" to chunkIds[index],
                    "projectId" to projectId,
                    "iterationId" to iterationId,
                    "taskId" to taskId,
                    "runId" to runId,
                    "artifactType" to "DOCUMENT_SNAPSHOT",
                    "sourcePath" to sourcePath,
                    "chunkIndex" to index,
                    "content" to content,
                    "chunkHash" to "$scope-chunk-hash-$index",
                    "tokenEstimate" to 12,
                    "sourceReference" to sourceReference(chunkIds[index], "$sourcePath#chunk-$index"),
                    "createdAt" to NOW,
                    "metadata" to mapOf("suite" to "fake-v2", "phase" to "retrieval"),
                ),
            )
        },
    )

    fun semanticRequest(query: String, limit: Int): Map<String, Any> = mapOf(
        "q" to query,
        "projectId" to projectId,
        "iterationId" to iterationId,
        "artifactType" to "DOCUMENT_SNAPSHOT",
        "sourcePath" to sourcePath,
        "taskId" to taskId,
        "runId" to runId,
        "metadataFilters" to mapOf("suite" to "fake-v2", "phase" to "retrieval"),
        "limit" to limit,
    )

    fun hybridRequest(query: String): Map<String, Any> = semanticRequest(query, limit = 1) + mapOf(
        "candidateLimit" to 10,
        "rrfK" to 60,
    )

    fun keywordUrl(query: String, limit: Int, cursor: String? = null): String =
        "/api/search/keyword?q=$query&projectId=$projectId&iterationId=$iterationId&artifactType=DOCUMENT_SNAPSHOT" +
            "&sourcePath=$sourcePath&taskId=$taskId&runId=$runId&limit=$limit" +
            (cursor?.let { "&cursor=$it" } ?: "")

    fun artifactUrl(limit: Int, cursor: String? = null): String =
        "/api/artifacts?projectId=$projectId&iterationId=$iterationId&sourceProjectId=$sourceProjectId" +
            "&sourceIterationId=$sourceIterationId&sourceDocumentId=$sourceDocumentId&sourceTaskGraphId=$sourceTaskGraphId" +
            "&sourceTaskId=$sourceTaskId&sourceRunId=$sourceRunId&artifactType=DOCUMENT_CHUNK&sourcePath=$sourcePath" +
            "&taskId=$taskId&runId=$runId&limit=$limit" + (cursor?.let { "&cursor=$it" } ?: "")

    fun graphSnapshotBody(): Map<String, Any> = mapOf(
        "projectId" to projectId,
        "iterationId" to iterationId,
        "nodes" to listOf(
            mapOf(
                "nodeId" to stableUuid("$scope-task-node"),
                "nodeKind" to "task",
                "naturalKey" to "task:$sourceTaskId",
                "label" to "고정 V2 task",
                "taskId" to taskId,
            ),
            mapOf(
                "nodeId" to stableUuid("$scope-decision-node"),
                "nodeKind" to "decision",
                "naturalKey" to "decision:fake-v2",
                "label" to "Offline fake provider",
            ),
        ),
        "edges" to listOf(
            mapOf(
                "edgeId" to stableUuid("$scope-graph-edge"),
                "fromNodeId" to stableUuid("$scope-task-node"),
                "toNodeId" to stableUuid("$scope-decision-node"),
                "edgeType" to "DERIVED_FROM",
            ),
        ),
    )
}

private fun sourceReference(canonicalId: String, path: String): Map<String, String> = mapOf(
    "canonicalServerId" to canonicalId,
    "uri" to "file:///repo/$path",
    "path" to path,
)

private fun stableUuid(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()

private const val NOW = "2026-07-24T00:00:00Z"
