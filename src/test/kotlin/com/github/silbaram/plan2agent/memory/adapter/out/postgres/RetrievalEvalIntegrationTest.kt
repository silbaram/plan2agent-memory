@file:Suppress("DEPRECATION")

package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.github.silbaram.plan2agent.memory.application.port.out.KeywordSearchPort
import com.github.silbaram.plan2agent.memory.application.port.out.VectorSearchPort
import com.github.silbaram.plan2agent.memory.application.usecase.DocumentChunkWrite
import com.github.silbaram.plan2agent.memory.application.usecase.HybridSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.KeywordSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.RegisterIterationCommand
import com.github.silbaram.plan2agent.memory.application.usecase.RegisterProjectCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentChunksCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentSnapshotCommand
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.ReadUseCaseService
import com.github.silbaram.plan2agent.memory.application.usecase.WriteUseCaseService
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSet
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingStorageType
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.math.ln

@SpringBootTest
class RetrievalEvalIntegrationTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var writeUseCase: WriteUseCaseService

    @Autowired
    private lateinit var readUseCase: ReadUseCaseService

    @Autowired
    private lateinit var keywordSearch: KeywordSearchPort

    @Autowired
    private lateinit var vectorSearch: VectorSearchPort

    @BeforeEach
    fun cleanDatabase() {
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
    fun `fixed retrieval eval corpus protects keyword vector and hybrid recall`() {
        val corpus = saveEvalCorpus()
        val cases = listOf(
            RetrievalEvalCase(
                name = "payment retry",
                keywordQuery = "payment retry idempotent",
                embedding = Embedding(listOf(1.0f, 0.0f)),
                relevantChunkIds = setOf(corpus.paymentChunkId.value),
            ),
            RetrievalEvalCase(
                name = "migration rollback",
                keywordQuery = "migration rollback schema",
                embedding = Embedding(listOf(0.0f, 1.0f)),
                relevantChunkIds = setOf(corpus.migrationChunkId.value),
            ),
        )

        val keywordResults = cases.map { evalCase ->
            RetrievalEvalHarness.evaluate(
                evalCase = evalCase,
                returnedChunkIds = keywordSearch.search(
                    KeywordSearchQuery(
                        query = evalCase.keywordQuery,
                        projectId = corpus.projectId,
                        iterationId = corpus.iterationId,
                        limit = 3,
                    ),
                ).items.mapNotNull { it.chunkId?.value },
                k = 3,
            )
        }
        val vectorResults = cases.map { evalCase ->
            RetrievalEvalHarness.evaluate(
                evalCase = evalCase,
                returnedChunkIds = vectorSearch.search(
                    VectorSearchQuery(
                        embedding = evalCase.embedding,
                        embeddingModel = corpus.embeddingSet.embeddingModel,
                        embeddingDimension = corpus.embeddingSet.embeddingDimension,
                        embeddingVersion = corpus.embeddingSet.embeddingVersion,
                        distanceMetric = DistanceMetric.COSINE,
                        projectId = corpus.projectId,
                        iterationId = corpus.iterationId,
                        limit = 3,
                    ),
                ).items.mapNotNull { it.chunkId?.value },
                k = 3,
            )
        }
        val hybridResults = cases.map { evalCase ->
            RetrievalEvalHarness.evaluate(
                evalCase = evalCase,
                returnedChunkIds = readUseCase.hybridSearch(
                    HybridSearchQuery(
                        query = evalCase.keywordQuery,
                        embedding = evalCase.embedding,
                        embeddingModel = corpus.embeddingSet.embeddingModel,
                        embeddingDimension = corpus.embeddingSet.embeddingDimension,
                        embeddingVersion = corpus.embeddingSet.embeddingVersion,
                        distanceMetric = DistanceMetric.COSINE,
                        projectId = corpus.projectId,
                        iterationId = corpus.iterationId,
                        candidateLimit = 4,
                        limit = 3,
                    ),
                ).items.mapNotNull { it.chunkId?.value },
                k = 3,
            )
        }

        assertThat(keywordResults).allSatisfy {
            assertThat(it.recallAtK).describedAs("${it.caseName} keyword recall@3").isEqualTo(1.0)
            assertThat(it.ndcgAtK).describedAs("${it.caseName} keyword nDCG@3").isGreaterThanOrEqualTo(0.5)
        }
        assertThat(vectorResults).allSatisfy {
            assertThat(it.recallAtK).describedAs("${it.caseName} vector recall@3").isEqualTo(1.0)
            assertThat(it.ndcgAtK).describedAs("${it.caseName} vector nDCG@3").isGreaterThanOrEqualTo(0.5)
        }
        assertThat(hybridResults).allSatisfy {
            assertThat(it.recallAtK).describedAs("${it.caseName} hybrid recall@3").isEqualTo(1.0)
            assertThat(it.ndcgAtK).describedAs("${it.caseName} hybrid nDCG@3").isGreaterThanOrEqualTo(0.5)
        }
    }

    private fun saveEvalCorpus(): EvalCorpus {
        val projectId = ProjectId(stableUuid("retrieval-eval-project"))
        val project = writeUseCase.registerProject(
            RegisterProjectCommand(
                id = projectId,
                sourceProjectId = SourceProjectId("source-retrieval-eval-project"),
                name = "Retrieval Eval Project",
                canonicalServerId = CanonicalServerId(projectId.value),
                rootPath = "/repo/retrieval-eval",
                sourceReference = sourceReference(projectId.value, "projects/retrieval-eval"),
                createdAt = now,
            ),
        )
        val iterationId = IterationId(stableUuid("retrieval-eval-iteration"))
        val iteration = writeUseCase.registerIteration(
            RegisterIterationCommand(
                id = iterationId,
                projectId = project.id,
                sourceIterationId = SourceIterationId("source-retrieval-eval-iteration"),
                label = "Retrieval Eval Iteration",
                status = IterationStatus.ACTIVE,
                sourceReference = sourceReference(iterationId.value, "iterations/retrieval-eval"),
                createdAt = now,
            ),
        )
        val documentId = DocumentId(stableUuid("retrieval-eval-document"))
        val document = writeUseCase.saveDocumentSnapshot(
            SaveDocumentSnapshotCommand(
                id = documentId,
                projectId = project.id,
                iterationId = iteration.id,
                sourceDocumentId = SourceDocumentId("source-retrieval-eval-document"),
                sourcePath = "docs/retrieval-eval.md",
                snapshotVersion = 1,
                artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                title = "Retrieval Eval Corpus",
                content = "Fixed retrieval eval corpus for keyword, vector, and hybrid recall guardrails.",
                contentHash = ContentHash("retrieval-eval-document-hash"),
                sourceReference = sourceReference(documentId.value, "docs/retrieval-eval.md"),
                capturedAt = now,
                createdAt = now,
            ),
        )
        val embeddingSet = EmbeddingSet(
            id = EmbeddingSetId(stableUuid("retrieval-eval-embedding-set")),
            projectId = project.id,
            embeddingModel = "retrieval-eval-embedding",
            embeddingDimension = 2,
            embeddingVersion = "v1",
            distanceMetric = DistanceMetric.COSINE,
            storageType = EmbeddingStorageType.VECTOR_INDEX,
            createdAt = now,
        )
        val writes = listOf(
            chunkWrite(
                scope = "payment",
                documentId = document.id,
                projectId = project.id,
                iterationId = iteration.id,
                index = 0,
                content = "Payment retry policy handles idempotent checkout failures and refund retries.",
                embeddingSet = embeddingSet,
                embedding = Embedding(listOf(1.0f, 0.0f)),
            ),
            chunkWrite(
                scope = "migration",
                documentId = document.id,
                projectId = project.id,
                iterationId = iteration.id,
                index = 1,
                content = "Database migration rollback plan restores schema changes after failed deploys.",
                embeddingSet = embeddingSet,
                embedding = Embedding(listOf(0.0f, 1.0f)),
            ),
            chunkWrite(
                scope = "observability",
                documentId = document.id,
                projectId = project.id,
                iterationId = iteration.id,
                index = 2,
                content = "Metrics dashboards expose latency counters and search throughput timers.",
                embeddingSet = embeddingSet,
                embedding = Embedding(listOf(0.7f, 0.7f)),
            ),
            chunkWrite(
                scope = "auth",
                documentId = document.id,
                projectId = project.id,
                iterationId = iteration.id,
                index = 3,
                content = "Local token authentication protects API calls in development environments.",
                embeddingSet = embeddingSet,
                embedding = Embedding(listOf(-1.0f, 0.0f)),
            ),
        )
        val chunks = writeUseCase.saveDocumentChunks(SaveDocumentChunksCommand(documentId = document.id, chunks = writes))
        return EvalCorpus(
            projectId = project.id,
            iterationId = iteration.id,
            embeddingSet = embeddingSet,
            paymentChunkId = chunks.single { it.chunkIndex == 0 }.id,
            migrationChunkId = chunks.single { it.chunkIndex == 1 }.id,
        )
    }

    private fun chunkWrite(
        scope: String,
        documentId: DocumentId,
        projectId: ProjectId,
        iterationId: IterationId,
        index: Int,
        content: String,
        embeddingSet: EmbeddingSet,
        embedding: Embedding,
    ): DocumentChunkWrite {
        val chunkId = DocumentChunkId(stableUuid("retrieval-eval-$scope-chunk"))
        return DocumentChunkWrite(
            chunk = DocumentChunk(
                id = chunkId,
                projectId = projectId,
                iterationId = iterationId,
                documentId = documentId,
                artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                sourcePath = "docs/retrieval-eval.md",
                chunkIndex = index,
                content = content,
                chunkHash = ContentHash("retrieval-eval-$scope-chunk-hash"),
                tokenEstimate = content.split(Regex("\\s+")).size,
                sourceReference = sourceReference(chunkId.value, "docs/retrieval-eval.md#chunk-$index"),
                createdAt = now.plusSeconds(index.toLong()),
            ),
            embeddingSet = embeddingSet,
            embedding = embedding,
            embeddingHash = ContentHash("retrieval-eval-$scope-embedding-hash"),
        )
    }

    companion object {
        private val pgvectorImage = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres")
        @JvmStatic
        val postgres: RetrievalEvalPgVectorContainer = RetrievalEvalPgVectorContainer(pgvectorImage)
            .withDatabaseName("p2a_memory_eval_test")
            .withUsername("test")
            .withPassword("test")

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
        fun stopContainer() {
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

class RetrievalEvalPgVectorContainer(imageName: DockerImageName) :
    PostgreSQLContainer<RetrievalEvalPgVectorContainer>(imageName)

data class RetrievalEvalCase(
    val name: String,
    val keywordQuery: String,
    val embedding: Embedding,
    val relevantChunkIds: Set<String>,
)

data class RetrievalEvalResult(
    val caseName: String,
    val recallAtK: Double,
    val ndcgAtK: Double,
    val returnedChunkIds: List<String>,
)

object RetrievalEvalHarness {
    fun evaluate(
        evalCase: RetrievalEvalCase,
        returnedChunkIds: List<String>,
        k: Int,
    ): RetrievalEvalResult {
        require(k > 0) { "k must be positive" }
        require(evalCase.relevantChunkIds.isNotEmpty()) { "relevantChunkIds must not be empty" }
        val topK = returnedChunkIds.take(k)
        val hits = topK.count { it in evalCase.relevantChunkIds }
        val recall = hits.toDouble() / evalCase.relevantChunkIds.size.toDouble()
        val dcg = topK.mapIndexed { index, chunkId ->
            if (chunkId in evalCase.relevantChunkIds) 1.0 / log2(index + 2.0) else 0.0
        }.sum()
        val idealDcg = (1..minOf(k, evalCase.relevantChunkIds.size)).sumOf { rank ->
            1.0 / log2(rank + 1.0)
        }
        return RetrievalEvalResult(
            caseName = evalCase.name,
            recallAtK = recall,
            ndcgAtK = if (idealDcg == 0.0) 0.0 else dcg / idealDcg,
            returnedChunkIds = topK,
        )
    }

    private fun log2(value: Double): Double =
        ln(value) / ln(2.0)
}

private data class EvalCorpus(
    val projectId: ProjectId,
    val iterationId: IterationId,
    val embeddingSet: EmbeddingSet,
    val paymentChunkId: DocumentChunkId,
    val migrationChunkId: DocumentChunkId,
)

private val now: Instant = Instant.parse("2026-07-06T00:00:00Z")

private fun sourceReference(canonicalId: String, path: String): SourceReference =
    SourceReference(
        canonicalServerId = CanonicalServerId(canonicalId),
        uri = "file:///repo/$path",
        path = path,
    )

private fun stableUuid(seed: String): String =
    UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
