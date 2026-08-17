@file:Suppress("DEPRECATION")

package com.github.silbaram.plan2agent.memory.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "p2a.security.token=p2a-cli-integration-token",
        "p2a.embedding.provider=none",
        "p2a.memory.embedding.worker.enabled=false",
        "p2a.memory.scheduling.enabled=false",
    ],
)
@Import(P2aCliRequestCaptureConfiguration::class)
class P2aCliIntegrationTest {
    @TempDir
    private lateinit var tempDir: Path

    @LocalServerPort
    private var serverPort: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var requestCapture: P2aCliRequestCapture

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun cleanDatabaseAndCapture() {
        requestCapture.reset()
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
    fun `actual P2A planning docs push delegates chunks and converges on retry and concurrent backfill`() {
        val fixtureSource = Path.of(
            requireNotNull(javaClass.getResource("/fixtures/planning-docs")) {
                "Missing p2aCliIntegrationTest planning-docs fixture"
            }.toURI(),
        )
        val fixtureRoot = tempDir.resolve("planning-docs")
        copyFixtureTree(fixtureSource, fixtureRoot)

        val first = runP2aPush(fixtureRoot)
        assertThat(first.path("result").path("chunks").asInt()).isZero()
        assertThat(first.path("result").path("serverGeneratedChunks").asInt()).isEqualTo(3)
        assertThat(first.path("serverChunking").path("strategy").asText()).isEqualTo("paragraph-2000")
        assertThat(first.path("serverChunking").path("targetSnapshots").asInt()).isEqualTo(3)

        val firstSnapshotRequests = requestCapture.snapshotRequests()
        assertThat(firstSnapshotRequests).hasSize(3)
        firstSnapshotRequests.forEach { request ->
            assertThat(request.has("chunks")).isFalse()
            assertThat(request.path("chunking").fieldNames().asSequence().toList())
                .containsExactly("strategy")
            assertThat(request.path("chunking").path("strategy").asText()).isEqualTo("paragraph-2000")
        }
        assertThat(requestCapture.bulkRequestCount()).isZero()

        val persistedChunks = persistedChunks()
        assertThat(persistedChunks).hasSize(3)
        val expectedContentByPath = planningMarkdownPaths.associateWith { relativePath ->
            paragraph2000Content(Files.readString(fixtureRoot.resolve(relativePath)))
        }
        persistedChunks.forEach { chunk ->
            val expectedContent = expectedContentByPath.getValue(chunk.sourcePath)
            val expectedHash = sha256("0\n$expectedContent")
            assertThat(chunk.chunkIndex).isZero()
            assertThat(chunk.content).isEqualTo(expectedContent)
            assertThat(chunk.chunkHash).isEqualTo(expectedHash)
            assertThat(chunk.chunkId).isEqualTo(stableChunkId(chunk.documentId, expectedHash))
            assertThat(chunk.sourceChunkId).endsWith(":chunk-0")
            assertThat(chunk.parentDocumentId).isEqualTo(chunk.documentId)
            assertThat(chunk.chunkStrategy).isEqualTo("paragraph-2000")
        }
        assertPendingJobs(persistedChunks.size)

        val documentIds = ids("documents", "document_id")
        val chunkIds = ids("document_chunks", "chunk_id")
        val jobIds = ids("embedding_jobs", "embedding_job_id")
        val repeated = runP2aPush(fixtureRoot)
        assertThat(repeated.path("result").path("chunks").asInt()).isZero()
        assertThat(repeated.path("result").path("serverGeneratedChunks").asInt()).isEqualTo(3)
        assertThat(ids("documents", "document_id")).isEqualTo(documentIds)
        assertThat(ids("document_chunks", "chunk_id")).isEqualTo(chunkIds)
        assertThat(ids("embedding_jobs", "embedding_job_id")).isEqualTo(jobIds)
        assertThat(requestCapture.snapshotRequests()).hasSize(6)
        assertThat(requestCapture.bulkRequestCount()).isZero()
        assertPendingJobs(3)

        val snapshotOnlyRequest = concurrentSnapshotRequest(firstSnapshotRequests.first())
        val snapshotOnlyResponse = postSnapshot(snapshotOnlyRequest)
        assertThat(snapshotOnlyResponse.statusCode()).isEqualTo(201)
        assertThat(objectMapper.readTree(snapshotOnlyResponse.body()).has("chunking")).isFalse()
        assertThat(rowCount("documents")).isEqualTo(4)
        assertThat(rowCount("document_chunks")).isEqualTo(3)
        assertThat(rowCount("embedding_jobs")).isEqualTo(3)

        val optInRequest = snapshotOnlyRequest.deepCopy().apply {
            set<ObjectNode>(
                "chunking",
                objectMapper.createObjectNode().put("strategy", "paragraph-2000"),
            )
        }
        val responses = postConcurrently(optInRequest)
        assertThat(responses.map { it.statusCode() }).containsExactly(201, 201)
        val responseBodies = responses.map { objectMapper.readTree(it.body()) }
        assertThat(responseBodies.map { it.path("documentId").asText() }).containsOnly(CONCURRENT_DOCUMENT_ID)
        assertThat(responseBodies.map { it.path("chunking").path("chunkCount").asInt() }).containsOnly(1)
        assertThat(rowCount("documents")).isEqualTo(4)
        assertThat(rowCount("document_chunks")).isEqualTo(4)
        assertThat(rowCount("embedding_jobs")).isEqualTo(4)
        assertPendingJobs(4)
        assertThat(requestCapture.bulkRequestCount()).isZero()
    }

    private fun runP2aPush(fixtureRoot: Path): JsonNode {
        val script = Path.of(requireNotNull(System.getenv("P2A_CLI_SCRIPT"))).toAbsolutePath().normalize()
        val process = ProcessBuilder(
            "node",
            script.toString(),
            "memory",
            "push",
            "--artifacts",
            fixtureRoot.toString(),
            "--profile",
            "planning-docs",
            "--server",
            serverUrl,
            "--token",
            LOCAL_TOKEN,
            "--yes",
            "--json",
        ).start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw AssertionError("Actual P2A CLI push did not finish within 60 seconds")
        }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        assertThat(process.exitValue())
            .withFailMessage("Actual P2A CLI push failed. stdout=%s stderr=%s", stdout, stderr)
            .isZero()
        return objectMapper.readTree(stdout)
    }

    private fun copyFixtureTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination)
                }
            }
        }
    }

    private fun concurrentSnapshotRequest(capturedRequest: JsonNode): ObjectNode {
        val request = capturedRequest.deepCopy<ObjectNode>()
        request.remove("chunking")
        request.put("documentId", CONCURRENT_DOCUMENT_ID)
        request.put("sourceDocumentId", "webhook-api-service:v1:concurrent_spec")
        request.put("sourcePath", CONCURRENT_SOURCE_PATH)
        request.put("content", CONCURRENT_CONTENT)
        request.put("contentHash", sha256(CONCURRENT_CONTENT))

        (request.path("sourceReference") as ObjectNode).apply {
            put("canonicalServerId", CONCURRENT_DOCUMENT_ID)
            put("uri", "file:///integration/$CONCURRENT_SOURCE_PATH")
            put("path", CONCURRENT_SOURCE_PATH)
        }
        (request.path("metadata") as ObjectNode).apply {
            put("sourceDocumentId", "webhook-api-service:v1:concurrent_spec")
            put("sourcePath", CONCURRENT_SOURCE_PATH)
            put("contentHash", "sha256:${sha256(CONCURRENT_CONTENT)}")
        }
        return request
    }

    private fun postConcurrently(body: ObjectNode): List<HttpResponse<String>> {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        return try {
            val futures = List(2) {
                executor.submit<HttpResponse<String>> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "Concurrent snapshot start timed out" }
                    postSnapshot(body)
                }
            }
            check(ready.await(10, TimeUnit.SECONDS)) { "Concurrent snapshot workers were not ready" }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun postSnapshot(body: JsonNode): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(URI.create("$serverUrl/api/documents/snapshots"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(LOCAL_TOKEN_HEADER, LOCAL_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun persistedChunks(): List<PersistedChunk> =
        jdbc.query(
            """
            SELECT d.document_id::text AS document_id,
                   d.source_path,
                   c.chunk_id::text AS chunk_id,
                   c.chunk_index,
                   c.chunk_hash,
                   c.content,
                   c.metadata ->> 'sourceChunkId' AS source_chunk_id,
                   c.metadata ->> 'parentDocumentId' AS parent_document_id,
                   c.metadata ->> 'chunkStrategy' AS chunk_strategy
            FROM document_chunks c
            JOIN documents d ON d.document_id = c.document_id
            ORDER BY d.source_path, c.chunk_index
            """.trimIndent(),
        ) { result, _ ->
            PersistedChunk(
                documentId = result.getString("document_id"),
                sourcePath = result.getString("source_path"),
                chunkId = result.getString("chunk_id"),
                chunkIndex = result.getInt("chunk_index"),
                chunkHash = result.getString("chunk_hash"),
                content = result.getString("content"),
                sourceChunkId = result.getString("source_chunk_id"),
                parentDocumentId = result.getString("parent_document_id"),
                chunkStrategy = result.getString("chunk_strategy"),
            )
        }

    private fun assertPendingJobs(expected: Int) {
        assertThat(rowCount("embedding_jobs")).isEqualTo(expected.toLong())
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embedding_jobs WHERE status = 'pending'",
                Long::class.java,
            ),
        ).isEqualTo(expected.toLong())
        assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT chunk_id) FROM embedding_jobs",
                Long::class.java,
            ),
        ).isEqualTo(expected.toLong())
    }

    private fun ids(table: String, column: String): List<String> =
        jdbc.queryForList("SELECT $column::text FROM $table ORDER BY $column", String::class.java)

    private fun rowCount(table: String): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java) ?: 0L

    private fun paragraph2000Content(content: String): String =
        content.split(Regex("\\n{2,}"))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n\n")

    private fun stableChunkId(documentId: String, chunkHash: String): String {
        val hex = sha256("[\"p2a-chunk\",\"$documentId\",0,\"$chunkHash\"]").toCharArray()
        hex[12] = '5'
        hex[16] = ((hex[16].digitToInt(16) and 0x3) or 0x8).toString(16).single()
        val value = hex.concatToString(0, 32)
        return listOf(
            value.substring(0, 8),
            value.substring(8, 12),
            value.substring(12, 16),
            value.substring(16, 20),
            value.substring(20, 32),
        ).joinToString("-")
    }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
        )

    private val serverUrl: String
        get() = "http://127.0.0.1:$serverPort"

    companion object {
        private const val LOCAL_TOKEN_HEADER = "X-P2A-Local-Token"
        private const val LOCAL_TOKEN = "p2a-cli-integration-token"
        private const val CONCURRENT_DOCUMENT_ID = "99999999-9999-5999-8999-999999999999"
        private const val CONCURRENT_SOURCE_PATH = "iterations/v1/gate-b-spec/concurrent.md"
        private const val CONCURRENT_CONTENT = "Concurrent backfill paragraph.\n\nSecond paragraph."

        private val planningMarkdownPaths = listOf(
            "iterations/v1/gate-a-intake/intake.md",
            "iterations/v1/gate-b-spec/implementation-plan.md",
            "iterations/v1/gate-b-spec/product-spec.md",
        )

        private val pgvectorImage = DockerImageName.parse(
            "pgvector/pgvector:0.8.5-pg17-bookworm@sha256:" +
                "d2ef61f42ef767baa5a1475393303cc235bcd92febd9d7014eddb48b41f3bad0",
        ).asCompatibleSubstituteFor("postgres")

        @JvmStatic
        val postgres: P2aCliPgVectorContainer = P2aCliPgVectorContainer(pgvectorImage)
            .withDatabaseName("p2a_memory_cli_integration_test")
            .withUsername("p2a")
            .withPassword("p2a")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            if (!postgres.isRunning) postgres.start()
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
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
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

@TestConfiguration(proxyBeanMethods = false)
class P2aCliRequestCaptureConfiguration {
    @Bean
    fun p2aCliRequestCapture(): P2aCliRequestCapture = P2aCliRequestCapture()

    @Bean
    fun p2aCliCaptureFilter(
        capture: P2aCliRequestCapture,
        objectMapper: ObjectMapper,
    ): FilterRegistrationBean<OncePerRequestFilter> {
        val filter: OncePerRequestFilter = object : OncePerRequestFilter() {
            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain,
            ) {
                val wrapped = ContentCachingRequestWrapper(request, 1_048_576)
                try {
                    filterChain.doFilter(wrapped, response)
                } finally {
                    if (request.method == "POST") {
                        when (request.requestURI) {
                            "/api/documents/snapshots" -> capture.recordSnapshot(
                                objectMapper.readTree(wrapped.contentAsByteArray),
                            )
                            "/api/document-chunks/bulk" -> capture.recordBulk()
                        }
                    }
                }
            }
        }
        return FilterRegistrationBean(filter).apply {
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/api/*")
        }
    }
}

class P2aCliRequestCapture {
    private val snapshots = CopyOnWriteArrayList<JsonNode>()
    private val bulkRequests = AtomicInteger()

    fun recordSnapshot(body: JsonNode) {
        snapshots.add(body)
    }

    fun recordBulk() {
        bulkRequests.incrementAndGet()
    }

    fun snapshotRequests(): List<JsonNode> = snapshots.toList()

    fun bulkRequestCount(): Int = bulkRequests.get()

    fun reset() {
        snapshots.clear()
        bulkRequests.set(0)
    }
}

class P2aCliPgVectorContainer(imageName: DockerImageName) :
    PostgreSQLContainer<P2aCliPgVectorContainer>(imageName)

private data class PersistedChunk(
    val documentId: String,
    val sourcePath: String,
    val chunkId: String,
    val chunkIndex: Int,
    val chunkHash: String,
    val content: String,
    val sourceChunkId: String,
    val parentDocumentId: String,
    val chunkStrategy: String,
)
