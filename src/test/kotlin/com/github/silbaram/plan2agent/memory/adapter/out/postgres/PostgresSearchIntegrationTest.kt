package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.github.silbaram.plan2agent.memory.application.port.out.KeywordSearchPort
import com.github.silbaram.plan2agent.memory.application.port.out.VectorSearchPort
import com.github.silbaram.plan2agent.memory.application.usecase.DocumentChunkWrite
import com.github.silbaram.plan2agent.memory.application.usecase.KeywordSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.RegisterIterationCommand
import com.github.silbaram.plan2agent.memory.application.usecase.RegisterProjectCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentChunksCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentSnapshotCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveRunRecordCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveTaskGraphCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveTasksCommand
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.WriteUseCaseService
import com.github.silbaram.plan2agent.memory.domain.ArtifactRef
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSet
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingStorageType
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
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@SpringBootTest
class PostgresSearchIntegrationTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var writeUseCase: WriteUseCaseService

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
    fun `keyword search ranks chunk content first and returns opaque metadata rich scores`() {
        val fixture = saveProjectAndIteration("keyword-primary")
        val document = saveDocument(
            scope = "keyword-primary",
            fixture = fixture,
            sourcePath = "docs/keyword-primary.md",
            content = "Document level RAG needle candidate",
            metadata = mapOf("phase" to "gate-b"),
        )
        val taskRun = saveTaskAndRun("keyword-primary", fixture, document)
        val chunk = saveChunk(
            scope = "keyword-primary",
            document = document,
            content = "Chunk CONTENT contains RAG context and API path /api/search before a later needle.",
            taskRun = taskRun,
            metadata = mapOf("phase" to "gate-b", "kind" to "decision"),
        )

        val matches = keywordSearch.search(
            KeywordSearchQuery(
                query = "rag needle",
                projectId = fixture.project.id,
                iterationId = fixture.iteration.id,
                limit = 10,
            ),
        )

        assertThat(matches).hasSizeGreaterThanOrEqualTo(2)
        assertThat(matches.first().chunkId).isEqualTo(chunk.id)
        assertThat(matches.first().matchReason).isEqualTo("chunk.content")
        assertThat(matches.first().score).isGreaterThanOrEqualTo(0.0)
        assertThat(matches.first().metadata).contains(
            entry("phase", "gate-b"),
            entry("kind", "decision"),
            entry("sourceProjectId", fixture.project.sourceProjectId.value),
            entry("sourceIterationId", fixture.iteration.sourceIterationId.value),
            entry("sourceDocumentId", document.sourceDocumentId.value),
            entry("sourceTaskGraphId", taskRun.taskGraph.sourceTaskGraphId.value),
            entry("sourceTaskId", taskRun.task.sourceTaskId.value),
            entry("sourceRunId", taskRun.run.sourceRunId.value),
            entry("snapshotVersion", "1"),
        )

        val documentMatch = matches.single { it.documentId == document.id && it.chunkId == null }
        assertThat(documentMatch.matchReason).isEqualTo("document.content")
        assertThat(matches.first().score).isGreaterThan(documentMatch.score)
    }

    @Test
    fun `keyword search covers document content source path and artifact type secondary targets`() {
        val fixture = saveProjectAndIteration("keyword-secondary")
        val documentContentTarget = saveDocument(
            scope = "keyword-secondary-document",
            fixture = fixture,
            sourcePath = "docs/document-content.md",
            content = "The secondary document target token lives here.",
        )
        val sourcePathTarget = saveDocument(
            scope = "keyword-secondary-path",
            fixture = fixture,
            sourcePath = "docs/source-path-target.md",
            content = "Neutral content for path lookup.",
        )
        val artifactTypeTarget = saveDocument(
            scope = "keyword-secondary-artifact",
            fixture = fixture,
            sourcePath = "docs/artifact-type.md",
            content = "Neutral content for artifact type lookup.",
        )

        val documentMatches = keywordSearch.search(
            KeywordSearchQuery(query = "document target token", projectId = fixture.project.id),
        )
        assertThat(documentMatches.map { it.documentId }).contains(documentContentTarget.id)
        assertThat(documentMatches.single { it.documentId == documentContentTarget.id }.matchReason)
            .isEqualTo("document.content")

        val pathMatches = keywordSearch.search(
            KeywordSearchQuery(query = "source-path-target", projectId = fixture.project.id),
        )
        assertThat(pathMatches.single { it.documentId == sourcePathTarget.id }.matchReason)
            .isEqualTo("sourcePath")

        val artifactTypeMatches = keywordSearch.search(
            KeywordSearchQuery(
                query = "document_snapshot",
                projectId = fixture.project.id,
                sourcePath = artifactTypeTarget.sourcePath,
            ),
        )
        val artifactTypeMatch = artifactTypeMatches.single()
        assertThat(artifactTypeMatch.documentId).isEqualTo(artifactTypeTarget.id)
        assertThat(artifactTypeMatch.matchReason).isEqualTo("artifactType")
    }

    @Test
    fun `keyword search filters combine with AND semantics through the search port`() {
        val fixture = saveProjectAndIteration("keyword-filter")
        val matchingDocument = saveDocument(
            scope = "keyword-filter-match",
            fixture = fixture,
            sourcePath = "docs/filter.md",
            content = "Document body without the filter token.",
        )
        val matchingTaskRun = saveTaskAndRun("keyword-filter-match", fixture, matchingDocument)
        val matchingChunk = saveChunk(
            scope = "keyword-filter-match",
            document = matchingDocument,
            content = "filter-token appears in the matching chunk.",
            taskRun = matchingTaskRun,
            metadata = mapOf("phase" to "gate-d", "kind" to "decision"),
        )

        saveChunk(
            scope = "keyword-filter-wrong-metadata",
            document = matchingDocument,
            chunkIndex = 1,
            content = "filter-token appears with the wrong metadata.",
            taskRun = matchingTaskRun,
            metadata = mapOf("phase" to "gate-c", "kind" to "decision"),
        )
        saveChunk(
            scope = "keyword-filter-no-run",
            document = matchingDocument,
            chunkIndex = 2,
            content = "filter-token appears without the requested run relation.",
            metadata = mapOf("phase" to "gate-d", "kind" to "decision"),
        )
        val otherPathDocument = saveDocument(
            scope = "keyword-filter-wrong-path",
            fixture = fixture,
            sourcePath = "docs/other-filter.md",
            content = "Document body without the filter token.",
        )
        val otherPathTaskRun = saveTaskAndRun("keyword-filter-wrong-path", fixture, otherPathDocument)
        saveChunk(
            scope = "keyword-filter-wrong-path",
            document = otherPathDocument,
            content = "filter-token appears in a different source path.",
            taskRun = otherPathTaskRun,
            metadata = mapOf("phase" to "gate-d", "kind" to "decision"),
        )
        val otherProject = saveProjectAndIteration("keyword-filter-other-project")
        val otherProjectDocument = saveDocument(
            scope = "keyword-filter-other-project",
            fixture = otherProject,
            sourcePath = "docs/filter.md",
            content = "Document body without the filter token.",
        )
        saveChunk(
            scope = "keyword-filter-other-project",
            document = otherProjectDocument,
            content = "filter-token appears in another project.",
            metadata = mapOf("phase" to "gate-d", "kind" to "decision"),
        )

        val matches = keywordSearch.search(
            KeywordSearchQuery(
                query = "FILTER-token",
                projectId = fixture.project.id,
                iterationId = fixture.iteration.id,
                artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                sourcePath = matchingDocument.sourcePath,
                taskId = matchingTaskRun.task.id,
                runId = matchingTaskRun.run.id,
                metadataFilters = mapOf("phase" to "gate-d", "kind" to "decision"),
                limit = 10,
            ),
        )

        assertThat(matches.map { it.chunkId }).containsExactly(matchingChunk.id)
        assertThat(matches.single().matchReason).isEqualTo("chunk.content")
    }

    @Test
    fun `vector search ranks by exact cosine distance and filters by embedding set`() {
        val fixture = saveProjectAndIteration("vector-ranking")
        val document = saveDocument(
            scope = "vector-ranking",
            fixture = fixture,
            sourcePath = "docs/vector-ranking.md",
            content = "Vector ranking fixture.",
        )
        val embeddingSet = embeddingSet("vector-ranking-v1", fixture.project.id)
        val otherModelSet = embeddingSet(
            scope = "vector-ranking-other-model",
            projectId = fixture.project.id,
            model = "text-embedding-other",
        )
        val otherVersionSet = embeddingSet(
            scope = "vector-ranking-other-version",
            projectId = fixture.project.id,
            version = "v2",
        )

        val nearest = saveChunk(
            scope = "vector-ranking-nearest",
            document = document,
            content = "nearest cosine vector",
            embeddingSet = embeddingSet,
            embedding = Embedding(listOf(1.0f, 0.0f)),
        )
        val middle = saveChunk(
            scope = "vector-ranking-middle",
            document = document,
            chunkIndex = 1,
            content = "middle cosine vector",
            embeddingSet = embeddingSet,
            embedding = Embedding(listOf(0.7f, 0.7f)),
        )
        val farthest = saveChunk(
            scope = "vector-ranking-farthest",
            document = document,
            chunkIndex = 2,
            content = "farthest cosine vector",
            embeddingSet = embeddingSet,
            embedding = Embedding(listOf(0.0f, 1.0f)),
        )
        saveChunk(
            scope = "vector-ranking-other-model",
            document = document,
            chunkIndex = 3,
            content = "other model perfect vector",
            embeddingSet = otherModelSet,
            embedding = Embedding(listOf(1.0f, 0.0f)),
        )
        saveChunk(
            scope = "vector-ranking-other-version",
            document = document,
            chunkIndex = 4,
            content = "other version perfect vector",
            embeddingSet = otherVersionSet,
            embedding = Embedding(listOf(1.0f, 0.0f)),
        )

        val matches = vectorSearch.search(
            VectorSearchQuery(
                embedding = Embedding(listOf(1.0f, 0.0f)),
                embeddingModel = embeddingSet.embeddingModel,
                embeddingDimension = embeddingSet.embeddingDimension,
                embeddingVersion = embeddingSet.embeddingVersion,
                distanceMetric = DistanceMetric.COSINE,
                projectId = fixture.project.id,
                iterationId = fixture.iteration.id,
                limit = 10,
            ),
        )

        assertThat(matches.map { it.chunkId }).containsExactly(nearest.id, middle.id, farthest.id)
        assertThat(matches.zipWithNext().all { it.first.score <= it.second.score }).isTrue()
        assertThat(matches).allSatisfy {
            assertThat(it.distanceMetric).isEqualTo(DistanceMetric.COSINE)
            assertThat(it.embeddingModel).isEqualTo(embeddingSet.embeddingModel)
            assertThat(it.embeddingVersion).isEqualTo(embeddingSet.embeddingVersion)
            assertThat(it.score).isGreaterThanOrEqualTo(0.0)
            assertThat(it.metadata).containsEntry("snapshotVersion", "1")
        }
    }

    @Test
    fun `vector search handles empty embeddings and model dimension version mismatches`() {
        val fixture = saveProjectAndIteration("vector-validation")
        val document = saveDocument(
            scope = "vector-validation",
            fixture = fixture,
            sourcePath = "docs/vector-validation.md",
            content = "Vector validation fixture.",
        )
        val embeddingSet = embeddingSet("vector-validation", fixture.project.id)
        saveChunk(
            scope = "vector-validation",
            document = document,
            content = "validation vector",
            embeddingSet = embeddingSet,
            embedding = Embedding(listOf(1.0f, 0.0f)),
        )

        assertThatThrownBy { Embedding(emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least one dimension")
        assertThat(
            vectorSearch.search(
                VectorSearchQuery(
                    embedding = Embedding(listOf(1.0f, 0.0f)),
                    embeddingModel = "missing-model",
                    embeddingDimension = 2,
                    embeddingVersion = embeddingSet.embeddingVersion,
                    distanceMetric = DistanceMetric.COSINE,
                    projectId = fixture.project.id,
                ),
            ),
        ).isEmpty()
        assertThat(
            vectorSearch.search(
                VectorSearchQuery(
                    embedding = Embedding(listOf(1.0f, 0.0f)),
                    embeddingModel = embeddingSet.embeddingModel,
                    embeddingDimension = 2,
                    embeddingVersion = "missing-version",
                    distanceMetric = DistanceMetric.COSINE,
                    projectId = fixture.project.id,
                ),
            ),
        ).isEmpty()
        assertThatThrownBy {
            vectorSearch.search(
                VectorSearchQuery(
                    embedding = Embedding(listOf(1.0f, 0.0f, 0.0f)),
                    embeddingModel = embeddingSet.embeddingModel,
                    embeddingDimension = 3,
                    embeddingVersion = embeddingSet.embeddingVersion,
                    distanceMetric = DistanceMetric.COSINE,
                    projectId = fixture.project.id,
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not match stored embedding set dimensions")
    }

    private fun saveProjectAndIteration(scope: String): SearchProjectIterationFixture {
        val projectId = ProjectId(stableUuid("$scope-project"))
        val project = writeUseCase.registerProject(
            RegisterProjectCommand(
                id = projectId,
                sourceProjectId = SourceProjectId("source-project-$scope"),
                name = "Project $scope",
                canonicalServerId = CanonicalServerId(projectId.value),
                rootPath = "/repo/$scope",
                sourceReference = sourceReference(projectId.value, "projects/$scope"),
                createdAt = now,
            ),
        )
        val iterationId = IterationId(stableUuid("$scope-iteration"))
        val iteration = writeUseCase.registerIteration(
            RegisterIterationCommand(
                id = iterationId,
                projectId = project.id,
                sourceIterationId = SourceIterationId("source-iteration-$scope"),
                label = "Iteration $scope",
                status = IterationStatus.ACTIVE,
                sourceReference = sourceReference(iterationId.value, "iterations/$scope"),
                createdAt = now,
            ),
        )
        return SearchProjectIterationFixture(project, iteration)
    }

    private fun saveDocument(
        scope: String,
        fixture: SearchProjectIterationFixture,
        sourcePath: String,
        content: String,
        metadata: Map<String, String> = emptyMap(),
    ): DocumentSnapshot {
        val documentId = DocumentId(stableUuid("$scope-document"))
        return writeUseCase.saveDocumentSnapshot(
            SaveDocumentSnapshotCommand(
                id = documentId,
                projectId = fixture.project.id,
                iterationId = fixture.iteration.id,
                sourceDocumentId = SourceDocumentId("source-document-$scope"),
                sourcePath = sourcePath,
                snapshotVersion = 1,
                artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                title = "Document $scope",
                content = content,
                contentHash = ContentHash("document-hash-$scope"),
                sourceReference = sourceReference(documentId.value, sourcePath),
                capturedAt = now,
                createdAt = now,
                metadata = metadata,
            ),
        )
    }

    private fun saveTaskAndRun(
        scope: String,
        fixture: SearchProjectIterationFixture,
        document: DocumentSnapshot,
    ): SearchTaskRunFixture {
        val taskGraphId = TaskGraphId(stableUuid("$scope-task-graph"))
        val taskId = TaskId(stableUuid("$scope-task"))
        val taskGraph = writeUseCase.saveTaskGraph(
            SaveTaskGraphCommand(
                id = taskGraphId,
                projectId = fixture.project.id,
                iterationId = fixture.iteration.id,
                sourceTaskGraphId = SourceTaskGraphId("source-task-graph-$scope"),
                sourceDocumentId = document.sourceDocumentId,
                graphHash = ContentHash("task-graph-hash-$scope"),
                graphJson = """{"tasks":["${taskId.value}"]}""",
                taskIds = setOf(taskId),
                sourceReference = sourceReference(taskGraphId.value, "task-graphs/$scope.json"),
                createdAt = now,
            ),
        )
        val task = writeUseCase.saveTasks(
            SaveTasksCommand(
                graphId = taskGraph.id,
                tasks = listOf(
                    Task(
                        id = taskId,
                        projectId = fixture.project.id,
                        iterationId = fixture.iteration.id,
                        taskGraphId = taskGraph.id,
                        sourceTaskId = SourceTaskId("source-task-$scope"),
                        title = "Task $scope",
                        description = "Search task $scope",
                        status = TaskStatus.READY,
                        targetArea = "search-tests",
                        acceptanceCriteria = listOf("Search fixture $scope"),
                        sourceReference = sourceReference(taskId.value, "task-graphs/$scope.json#task"),
                        createdAt = now,
                    ),
                ),
            ),
        ).single()
        val runId = RunId(stableUuid("$scope-run"))
        val run = writeUseCase.saveRunRecord(
            SaveRunRecordCommand(
                id = runId,
                projectId = fixture.project.id,
                iterationId = fixture.iteration.id,
                taskId = task.id,
                sourceRunId = SourceRunId("source-run-$scope"),
                status = RunStatus.FINISHED,
                agentTool = "codex",
                runJson = """{"status":"finished"}""",
                artifactRefs = listOf(
                    ArtifactRef(
                        artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                        artifactId = document.id.value,
                        sourcePath = document.sourcePath,
                    ),
                ),
                sourceReference = sourceReference(runId.value, "runs/$scope.json"),
                startedAt = now,
                finishedAt = now,
                createdAt = now,
            ),
        )
        return SearchTaskRunFixture(taskGraph, task, run)
    }

    private fun saveChunk(
        scope: String,
        document: DocumentSnapshot,
        content: String,
        chunkIndex: Int = 0,
        taskRun: SearchTaskRunFixture? = null,
        metadata: Map<String, String> = emptyMap(),
        embeddingSet: EmbeddingSet? = null,
        embedding: Embedding? = null,
    ): DocumentChunk {
        val chunkId = DocumentChunkId(stableUuid("$scope-chunk"))
        return writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(
                    DocumentChunkWrite(
                        chunk = DocumentChunk(
                            id = chunkId,
                            projectId = document.projectId,
                            iterationId = document.iterationId,
                            documentId = document.id,
                            taskId = taskRun?.task?.id,
                            runId = taskRun?.run?.id,
                            artifactType = document.artifactType,
                            sourcePath = document.sourcePath,
                            chunkIndex = chunkIndex,
                            content = content,
                            chunkHash = ContentHash("chunk-hash-$scope"),
                            tokenEstimate = content.split(Regex("\\s+")).size,
                            sourceReference = sourceReference(chunkId.value, "${document.sourcePath}#chunk-$chunkIndex"),
                            createdAt = now.plusSeconds(chunkIndex.toLong()),
                            metadata = metadata,
                        ),
                        embeddingSet = embeddingSet,
                        embedding = embedding,
                        embeddingHash = embedding?.let { ContentHash("embedding-hash-$scope") },
                    ),
                ),
            ),
        ).single()
    }

    companion object {
        private val pgvectorImage = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres")

        @JvmStatic
        val postgres: PgVectorContainer = PgVectorContainer(pgvectorImage)
            .withDatabaseName("p2a_memory_search_test")
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

private data class SearchProjectIterationFixture(
    val project: Project,
    val iteration: Iteration,
)

private data class SearchTaskRunFixture(
    val taskGraph: TaskGraph,
    val task: Task,
    val run: RunRecord,
)

private fun embeddingSet(
    scope: String,
    projectId: ProjectId,
    model: String = "text-embedding-test",
    version: String = "v1",
    dimension: Int = 2,
): EmbeddingSet =
    EmbeddingSet(
        id = EmbeddingSetId(stableUuid("$scope-embedding-set")),
        projectId = projectId,
        embeddingModel = model,
        embeddingDimension = dimension,
        embeddingVersion = version,
        distanceMetric = DistanceMetric.COSINE,
        storageType = EmbeddingStorageType.VECTOR_INDEX,
        createdAt = now,
    )

private fun sourceReference(canonicalId: String, path: String): SourceReference =
    SourceReference(
        canonicalServerId = CanonicalServerId(canonicalId),
        uri = "file:///repo/$path",
        path = path,
    )

private fun stableUuid(seed: String): String =
    UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()

private fun entry(key: String, value: String): Map.Entry<String, String> =
    java.util.AbstractMap.SimpleImmutableEntry(key, value)

private val now: Instant = Instant.parse("2026-06-29T00:00:00Z")
