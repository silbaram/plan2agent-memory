@file:Suppress("DEPRECATION")

package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactQueryPort
import com.github.silbaram.plan2agent.memory.application.port.out.ChunkEmbeddingStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentChunkStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentSnapshotStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.IterationStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ProjectStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.RunRecordStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskGraphStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskStorePort
import com.github.silbaram.plan2agent.memory.application.usecase.DocumentChunkWrite
import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentChunksCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentSnapshotCommand
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
import com.github.silbaram.plan2agent.memory.domain.TaskDependency
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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@SpringBootTest
class PostgresStorageIntegrationTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

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

    @Autowired
    private lateinit var documentChunkStore: DocumentChunkStorePort

    @Autowired
    private lateinit var chunkEmbeddingStore: ChunkEmbeddingStorePort

    @Autowired
    private lateinit var artifactQuery: ArtifactQueryPort

    @Autowired
    private lateinit var writeUseCase: WriteUseCaseService

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
    fun `flyway migration creates pgvector schema contracts`() {
        assertThat(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')",
                Boolean::class.java,
            ),
        ).isTrue()
        assertThat(tableNames()).contains(
            "projects",
            "iterations",
            "documents",
            "task_graphs",
            "tasks",
            "runs",
            "document_chunks",
            "embedding_sets",
            "chunk_embeddings",
        )
        assertThat(columnNames("documents")).contains(
            "document_id",
            "source_document_id",
            "source_path",
            "raw_source_path",
            "content_hash",
            "snapshot_version",
        )
        assertThat(columnNames("document_chunks")).contains(
            "chunk_id",
            "document_id",
            "task_id",
            "run_id",
            "raw_source_path",
            "chunk_hash",
        )
        assertThat(indexAndConstraintNames()).contains(
            "uq_documents_logical_snapshot_hash",
            "uq_documents_logical_snapshot_version",
            "uq_documents_project_iteration_source_document_hash",
            "uq_document_chunks_document_chunk_hash",
            "uq_chunk_embeddings_chunk_embedding_set",
            "idx_documents_latest_snapshot",
            "idx_document_chunks_artifact_filters",
            "idx_chunk_embeddings_embedding_set_id",
        )
    }

    @Test
    fun `storage adapters persist project iteration document graph task run and chunk records`() {
        val fixture = saveFixture("adapter-persistence")
        val remappedProject = projectStore.save(project("adapter-persistence-remap").copy(
            sourceProjectId = fixture.project.sourceProjectId,
        ))

        assertThat(projectStore.findById(fixture.project.id)).isEqualTo(remappedProject)
        assertThat(remappedProject.id).isEqualTo(fixture.project.id)
        assertThat(iterationStore.findById(fixture.iteration.id)).isEqualTo(fixture.iteration)
        assertThat(documentSnapshotStore.findById(fixture.document.id)).isEqualTo(fixture.document)
        assertThat(taskGraphStore.findById(fixture.taskGraph.id)).isEqualTo(fixture.taskGraph)
        assertThat(taskStore.findById(fixture.task.id)).isEqualTo(fixture.task)
        assertThat(runRecordStore.findById(fixture.run.id)).isEqualTo(fixture.run)
        assertThat(documentChunkStore.findByDocumentId(fixture.document.id)).containsExactly(fixture.chunk)
        assertThat(runRecordStore.findByTaskId(fixture.task.id)).containsExactly(fixture.run)
        assertThat(taskStore.findByGraphId(fixture.taskGraph.id)).containsExactly(fixture.task)
    }

    @Test
    fun `document snapshots and chunks are idempotent by content and chunk hash`() {
        val fixture = saveProjectAndIteration("idempotency")
        val first = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "idempotency-first",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/spec.md",
            contentHash = "doc-hash-a",
            content = "same content",
        ))
        val repeated = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "idempotency-repeat",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/spec.md",
            contentHash = "doc-hash-a",
            content = "same content",
        ))
        val changed = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "idempotency-changed",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/spec.md",
            contentHash = "doc-hash-b",
            content = "changed content",
        ))

        assertThat(repeated.id).isEqualTo(first.id)
        assertThat(changed.snapshotVersion).isEqualTo(2)
        assertThat(rowCount("documents")).isEqualTo(2)

        val firstChunk = writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = first.id,
                chunks = listOf(DocumentChunkWrite(chunk("idempotency-chunk-a", first, chunkHash = "chunk-hash-a"))),
            ),
        ).single()
        val repeatedChunk = writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = first.id,
                chunks = listOf(DocumentChunkWrite(chunk("idempotency-chunk-repeat", first, chunkHash = "chunk-hash-a"))),
            ),
        ).single()

        assertThat(repeatedChunk.id).isEqualTo(firstChunk.id)
        assertThat(rowCount("document_chunks")).isEqualTo(1)
    }

    @Test
    fun `embedding persistence is idempotent by chunk and embedding set`() {
        val fixture = saveProjectAndIteration("embedding")
        val document = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "embedding-doc",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/embedding.md",
            contentHash = "embedding-doc-hash",
        ))
        val firstChunk = chunk("embedding-chunk-a", document, chunkHash = "embedding-chunk-hash")
        val firstSet = embeddingSet("embedding-set-a", fixture.project.id, model = "text-embedding-test", version = "v1")
        val secondSet = embeddingSet("embedding-set-b", fixture.project.id, model = "text-embedding-test", version = "v2")

        writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(
                    DocumentChunkWrite(
                        chunk = firstChunk,
                        embeddingSet = firstSet,
                        embedding = Embedding(listOf(0.1f, 0.2f)),
                        embeddingHash = ContentHash("embedding-hash-a"),
                    ),
                ),
            ),
        )
        writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(
                    DocumentChunkWrite(
                        chunk = chunk("embedding-chunk-repeat", document, chunkHash = firstChunk.chunkHash.value),
                        embeddingSet = firstSet,
                        embedding = Embedding(listOf(0.1f, 0.2f)),
                        embeddingHash = ContentHash("embedding-hash-a"),
                    ),
                ),
            ),
        )
        writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(
                    DocumentChunkWrite(
                        chunk = chunk("embedding-chunk-second-set", document, chunkHash = firstChunk.chunkHash.value),
                        embeddingSet = secondSet,
                        embedding = Embedding(listOf(0.3f, 0.4f)),
                        embeddingHash = ContentHash("embedding-hash-b"),
                    ),
                ),
            ),
        )

        val savedChunk = documentChunkStore.findByDocumentId(document.id).single()
        assertThat(chunkEmbeddingStore.findByChunkId(savedChunk.id).map { it.embeddingSetId })
            .containsExactlyInAnyOrder(firstSet.id, secondSet.id)
        assertThat(rowCount("document_chunks")).isEqualTo(1)
        assertThat(rowCount("chunk_embeddings")).isEqualTo(2)

        assertThatThrownBy {
            writeUseCase.saveDocumentChunks(
                SaveDocumentChunksCommand(
                    documentId = document.id,
                    chunks = listOf(
                        DocumentChunkWrite(
                            chunk = chunk("embedding-chunk-conflict", document, chunkHash = firstChunk.chunkHash.value),
                            embeddingSet = firstSet,
                            embedding = Embedding(listOf(0.9f, 0.8f)),
                            embeddingHash = ContentHash("embedding-hash-conflict"),
                        ),
                    ),
                ),
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("different embedding hash or vector")
    }

    @Test
    fun `write use case normalizes source paths while preserving raw paths`() {
        val fixture = saveProjectAndIteration("path-normalization")
        val rawPath = """iterations\v1\gate-b-spec\spec.json"""
        val normalizedPath = "iterations/v1/gate-b-spec/spec.json"
        val first = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "path-normalization-first",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = rawPath,
            rawSourcePath = rawPath,
            contentHash = "path-normalized-hash",
        ))
        val repeated = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "path-normalization-repeat",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = normalizedPath,
            rawSourcePath = normalizedPath,
            contentHash = "path-normalized-hash",
        ))

        assertThat(first.sourcePath).isEqualTo(normalizedPath)
        assertThat(repeated.id).isEqualTo(first.id)
        assertThat(rowCount("documents")).isEqualTo(1)
        assertThat(documentPathRow(first.id)).containsEntry("source_path", normalizedPath)
        assertThat(documentPathRow(first.id)).containsEntry("raw_source_path", rawPath)

        val found = artifactQuery.findArtifacts(
            FindArtifactsQuery(
                projectId = fixture.project.id,
                iterationId = fixture.iteration.id,
                artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                sourcePath = normalizedPath,
            ),
        )
        assertThat(found.map { it.artifactId }).containsExactly(first.id.value)
    }

    private fun saveFixture(scope: String): StoredFixture {
        val base = saveProjectAndIteration(scope)
        val document = documentSnapshotStore.save(document(scope, base.project.id, base.iteration.id))
        val taskGraph = taskGraphStore.save(taskGraph(scope, base.project.id, base.iteration.id, document))
        val task = taskStore.saveAll(listOf(task(scope, base.project.id, base.iteration.id, taskGraph.id))).single()
        val run = runRecordStore.save(runRecord(scope, base.project.id, base.iteration.id, task.id, document))
        val chunk = documentChunkStore.saveAll(listOf(chunk(scope, document, task.id, run.id))).single()
        return StoredFixture(base.project, base.iteration, document, taskGraph, task, run, chunk)
    }

    private fun saveProjectAndIteration(scope: String): ProjectIterationFixture {
        val project = projectStore.save(project(scope))
        val iteration = iterationStore.save(iteration(scope, project.id))
        return ProjectIterationFixture(project, iteration)
    }

    private fun tableNames(): Set<String> =
        strings(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_type = 'BASE TABLE'
            """.trimIndent(),
        ).toSet()

    private fun columnNames(tableName: String): Set<String> =
        jdbc.query(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("column_name") },
            tableName,
        ).toSet()

    private fun indexAndConstraintNames(): Set<String> =
        (
            strings("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'") +
                strings(
                    """
                    SELECT constraint_name
                    FROM information_schema.table_constraints
                    WHERE table_schema = 'public'
                    """.trimIndent(),
                )
            ).toSet()

    private fun strings(sql: String): List<String> =
        jdbc.query(sql) { rs, _ -> rs.getString(1) }

    private fun rowCount(table: String): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java) ?: 0L

    private fun documentPathRow(documentId: DocumentId): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT source_path, raw_source_path FROM documents WHERE document_id = ?",
            UUID.fromString(documentId.value),
        )

    companion object {
        private val pgvectorImage = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres")

        @JvmStatic
        val postgres: PgVectorContainer = PgVectorContainer(pgvectorImage)
            .withDatabaseName("p2a_memory_test")
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

private data class ProjectIterationFixture(
    val project: Project,
    val iteration: Iteration,
)

private data class StoredFixture(
    val project: Project,
    val iteration: Iteration,
    val document: DocumentSnapshot,
    val taskGraph: TaskGraph,
    val task: Task,
    val run: RunRecord,
    val chunk: DocumentChunk,
)

class PgVectorContainer(imageName: DockerImageName) : PostgreSQLContainer<PgVectorContainer>(imageName)

private fun project(scope: String): Project {
    val id = ProjectId(stableUuid("$scope-project"))
    return Project(
        id = id,
        sourceProjectId = SourceProjectId("source-project-$scope"),
        name = "Project $scope",
        canonicalServerId = CanonicalServerId(id.value),
        rootPath = "/repo/$scope",
        sourceReference = sourceReference(id.value, "projects/$scope"),
        createdAt = now,
    )
}

private fun iteration(scope: String, projectId: ProjectId): Iteration {
    val id = IterationId(stableUuid("$scope-iteration"))
    return Iteration(
        id = id,
        projectId = projectId,
        sourceIterationId = SourceIterationId("source-iteration-$scope"),
        label = "Iteration $scope",
        status = IterationStatus.ACTIVE,
        sourceReference = sourceReference(id.value, "iterations/$scope"),
        createdAt = now,
    )
}

private fun document(scope: String, projectId: ProjectId, iterationId: IterationId): DocumentSnapshot {
    val id = DocumentId(stableUuid("$scope-document"))
    return DocumentSnapshot(
        id = id,
        projectId = projectId,
        iterationId = iterationId,
        sourceDocumentId = SourceDocumentId("source-document-$scope"),
        sourcePath = "docs/$scope.md",
        snapshotVersion = 1,
        artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
        title = "Document $scope",
        content = "Document content for $scope",
        contentHash = ContentHash("document-hash-$scope"),
        sourceReference = sourceReference(id.value, "docs/$scope.md"),
        capturedAt = now,
        createdAt = now,
    )
}

private fun taskGraph(
    scope: String,
    projectId: ProjectId,
    iterationId: IterationId,
    document: DocumentSnapshot,
): TaskGraph {
    val id = TaskGraphId(stableUuid("$scope-task-graph"))
    val taskId = TaskId(stableUuid("$scope-task"))
    return TaskGraph(
        id = id,
        projectId = projectId,
        iterationId = iterationId,
        sourceTaskGraphId = SourceTaskGraphId("source-task-graph-$scope"),
        sourceDocumentId = document.sourceDocumentId,
        graphHash = ContentHash("task-graph-hash-$scope"),
        graphJson = """{"tasks":["${taskId.value}"]}""",
        taskIds = setOf(taskId),
        sourceReference = sourceReference(id.value, "task-graphs/$scope.json"),
        createdAt = now,
    )
}

private fun task(scope: String, projectId: ProjectId, iterationId: IterationId, taskGraphId: TaskGraphId): Task {
    val id = TaskId(stableUuid("$scope-task"))
    return Task(
        id = id,
        projectId = projectId,
        iterationId = iterationId,
        taskGraphId = taskGraphId,
        sourceTaskId = SourceTaskId("source-task-$scope"),
        title = "Task $scope",
        description = "Task description for $scope",
        status = TaskStatus.READY,
        targetArea = "integration-tests",
        dependencies = emptySet(),
        acceptanceCriteria = listOf("Persist task $scope"),
        sourceReference = sourceReference(id.value, "task-graphs/$scope.json#task"),
        createdAt = now,
    )
}

private fun runRecord(
    scope: String,
    projectId: ProjectId,
    iterationId: IterationId,
    taskId: TaskId,
    document: DocumentSnapshot,
): RunRecord {
    val id = RunId(stableUuid("$scope-run"))
    return RunRecord(
        id = id,
        projectId = projectId,
        iterationId = iterationId,
        taskId = taskId,
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
        sourceReference = sourceReference(id.value, "runs/$scope.json"),
        startedAt = now,
        finishedAt = now,
        createdAt = now,
    )
}

private fun chunk(
    scope: String,
    document: DocumentSnapshot,
    taskId: TaskId? = null,
    runId: RunId? = null,
    chunkHash: String = "chunk-hash-$scope",
): DocumentChunk {
    val id = DocumentChunkId(stableUuid("$scope-chunk"))
    return DocumentChunk(
        id = id,
        projectId = document.projectId,
        iterationId = document.iterationId,
        documentId = document.id,
        taskId = taskId,
        runId = runId,
        artifactType = document.artifactType,
        sourcePath = document.sourcePath,
        chunkIndex = 0,
        content = "Chunk content for $scope",
        chunkHash = ContentHash(chunkHash),
        tokenEstimate = 6,
        sourceReference = sourceReference(id.value, document.sourcePath),
        createdAt = now,
    )
}

private fun documentCommand(
    scope: String,
    projectId: ProjectId,
    iterationId: IterationId,
    sourcePath: String,
    rawSourcePath: String = sourcePath,
    contentHash: String,
    content: String = "Document content for $scope",
): SaveDocumentSnapshotCommand {
    val id = DocumentId(stableUuid("$scope-document-command"))
    return SaveDocumentSnapshotCommand(
        id = id,
        projectId = projectId,
        iterationId = iterationId,
        sourceDocumentId = SourceDocumentId("source-document-$scope"),
        sourcePath = sourcePath,
        snapshotVersion = 1,
        artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
        title = "Document $scope",
        content = content,
        contentHash = ContentHash(contentHash),
        sourceReference = sourceReference(id.value, rawSourcePath),
        capturedAt = now,
        createdAt = now,
    )
}

private fun embeddingSet(
    scope: String,
    projectId: ProjectId,
    model: String,
    version: String,
): EmbeddingSet =
    EmbeddingSet(
        id = EmbeddingSetId(stableUuid("$scope-embedding-set")),
        projectId = projectId,
        embeddingModel = model,
        embeddingDimension = 2,
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

private val now: Instant = Instant.parse("2026-06-29T00:00:00Z")
