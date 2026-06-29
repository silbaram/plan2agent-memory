package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.out.ChunkEmbeddingStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentChunkStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentSnapshotStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingSetStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.IterationStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ProjectStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.RunRecordStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskGraphStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskStorePort
import com.github.silbaram.plan2agent.memory.domain.ArtifactRef
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ChunkEmbedding
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
import com.github.silbaram.plan2agent.memory.domain.SourceRunId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskId
import com.github.silbaram.plan2agent.memory.domain.Task
import com.github.silbaram.plan2agent.memory.domain.TaskGraph
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.TaskStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class WriteUseCaseServiceTest {
    private val ids = TestIds()
    private val stores = TestStores(ids)
    private val service = stores.service()

    @Test
    fun `document snapshot save returns existing logical hash before store save`() {
        val existing = stores.documentSnapshots.documents.first()
        val command = SaveDocumentSnapshotCommand(
            id = DocumentId(uuid(30)),
            projectId = ids.projectId,
            iterationId = ids.iterationId,
            sourceDocumentId = SourceDocumentId("source-doc"),
            sourcePath = existing.sourcePath,
            snapshotVersion = 1,
            artifactType = existing.artifactType,
            title = "Spec",
            content = "same",
            contentHash = existing.contentHash,
            capturedAt = now,
            createdAt = now,
        )

        val saved = service.saveDocumentSnapshot(command)

        assertThat(saved).isEqualTo(existing)
        assertThat(stores.documentSnapshots.saveCalls).isZero()
    }

    @Test
    fun `task graph save returns existing graph hash before store save`() {
        val existing = stores.taskGraphs.graphs.first()

        val saved = service.saveTaskGraph(
            SaveTaskGraphCommand(
                id = TaskGraphId(uuid(31)),
                projectId = ids.projectId,
                iterationId = ids.iterationId,
                sourceTaskGraphId = SourceTaskGraphId("new-source-graph"),
                sourceDocumentId = existing.sourceDocumentId,
                graphHash = existing.graphHash,
                graphJson = """{"same":true}""",
                createdAt = now,
            ),
        )

        assertThat(saved).isEqualTo(existing)
        assertThat(stores.taskGraphs.saveCalls).isZero()
    }

    @Test
    fun `document chunk save writes only new chunks and coordinates supplied embeddings`() {
        val existingChunk = stores.documentChunks.chunks.first()
        val newChunk = DocumentChunk(
            id = DocumentChunkId(uuid(41)),
            projectId = ids.projectId,
            iterationId = ids.iterationId,
            documentId = ids.documentId,
            artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
            sourcePath = "spec.md",
            chunkIndex = 1,
            content = "new chunk",
            chunkHash = ContentHash("chunk-new"),
            createdAt = now,
        )
        val embeddingSet = EmbeddingSet(
            id = EmbeddingSetId(uuid(50)),
            projectId = ids.projectId,
            embeddingModel = "text-embedding-test",
            embeddingDimension = 2,
            embeddingVersion = "v1",
            distanceMetric = DistanceMetric.COSINE,
            storageType = EmbeddingStorageType.VECTOR_INDEX,
            createdAt = now,
        )

        val saved = service.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = ids.documentId,
                chunks = listOf(
                    DocumentChunkWrite(chunk = existingChunk),
                    DocumentChunkWrite(
                        chunk = newChunk,
                        embeddingSet = embeddingSet,
                        embedding = Embedding(listOf(0.1f, 0.2f)),
                        embeddingHash = ContentHash("embedding-new"),
                    ),
                ),
            ),
        )

        assertThat(saved.map { it.id }).containsExactly(existingChunk.id, newChunk.id)
        assertThat(stores.documentChunks.savedBatches).hasSize(1)
        assertThat(stores.documentChunks.savedBatches.single().map(DocumentChunk::id)).containsExactly(newChunk.id)
        assertThat(stores.embeddingSets.resolved).containsExactly(embeddingSet)
        assertThat(stores.chunkEmbeddings.saved).hasSize(1)
        val savedEmbedding = stores.chunkEmbeddings.saved.single()
        assertThat(savedEmbedding.chunkId).isEqualTo(newChunk.id)
        assertThat(savedEmbedding.embeddingSetId).isEqualTo(embeddingSet.id)
        assertThat(savedEmbedding.embeddingHash).isEqualTo(ContentHash("embedding-new"))
    }

    @Test
    fun `run save keeps run json and artifact refs in one use case transaction`() {
        val artifactRef = ArtifactRef(
            artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
            artifactId = ids.documentId.value,
            sourcePath = "spec.md",
        )

        val saved = service.saveRunRecord(
            SaveRunRecordCommand(
                id = ids.runId,
                projectId = ids.projectId,
                iterationId = ids.iterationId,
                taskId = ids.taskId,
                sourceRunId = SourceRunId("source-run"),
                status = RunStatus.FINISHED,
                agentTool = "codex",
                runJson = """{"status":"finished"}""",
                artifactRefs = listOf(artifactRef),
                startedAt = now,
                finishedAt = now,
                createdAt = now,
            ),
        )

        assertThat(saved.runJson).isEqualTo("""{"status":"finished"}""")
        assertThat(saved.artifactRefs).containsExactly(artifactRef)
        assertThat(stores.runRecords.saved).singleElement().isEqualTo(saved)
    }
}

private class TestStores(ids: TestIds) {
    val projects = FakeProjectStore(ids.project)
    val iterations = FakeIterationStore(ids.iteration)
    val documentSnapshots = FakeDocumentSnapshotStore(ids.document)
    val taskGraphs = FakeTaskGraphStore(ids.taskGraph)
    val tasks = FakeTaskStore(ids.task)
    val runRecords = FakeRunRecordStore()
    val documentChunks = FakeDocumentChunkStore(ids.existingChunk)
    val embeddingSets = FakeEmbeddingSetStore()
    val chunkEmbeddings = FakeChunkEmbeddingStore()

    fun service(): WriteUseCaseService =
        WriteUseCaseService(
            projectStore = projects,
            iterationStore = iterations,
            documentSnapshotStore = documentSnapshots,
            taskGraphStore = taskGraphs,
            taskStore = tasks,
            runRecordStore = runRecords,
            documentChunkStore = documentChunks,
            embeddingSetStore = embeddingSets,
            chunkEmbeddingStore = chunkEmbeddings,
        )
}

private class FakeProjectStore(private val project: Project) : ProjectStorePort {
    override fun save(project: Project): Project = project
    override fun findById(id: ProjectId): Project? = project.takeIf { it.id == id }
}

private class FakeIterationStore(private val iteration: Iteration) : IterationStorePort {
    override fun save(iteration: Iteration): Iteration = iteration
    override fun findById(id: IterationId): Iteration? = iteration.takeIf { it.id == id }
    override fun findByProjectId(projectId: ProjectId): List<Iteration> =
        listOf(iteration).filter { it.projectId == projectId }
}

private class FakeDocumentSnapshotStore(val document: DocumentSnapshot) : DocumentSnapshotStorePort {
    val documents = mutableListOf(document)
    var saveCalls = 0

    override fun save(documentSnapshot: DocumentSnapshot): DocumentSnapshot {
        saveCalls += 1
        documents += documentSnapshot
        return documentSnapshot
    }

    override fun findById(id: DocumentId): DocumentSnapshot? = documents.firstOrNull { it.id == id }
    override fun findByIterationId(iterationId: IterationId): List<DocumentSnapshot> =
        documents.filter { it.iterationId == iterationId }
}

private class FakeTaskGraphStore(taskGraph: TaskGraph) : TaskGraphStorePort {
    val graphs = mutableListOf(taskGraph)
    var saveCalls = 0

    override fun save(taskGraph: TaskGraph): TaskGraph {
        saveCalls += 1
        graphs += taskGraph
        return taskGraph
    }

    override fun findById(id: TaskGraphId): TaskGraph? = graphs.firstOrNull { it.id == id }
    override fun findByIterationId(iterationId: IterationId): List<TaskGraph> =
        graphs.filter { it.iterationId == iterationId }
}

private class FakeTaskStore(private val task: Task) : TaskStorePort {
    override fun saveAll(tasks: List<Task>): List<Task> = tasks
    override fun findById(id: TaskId): Task? = task.takeIf { it.id == id }
    override fun findByGraphId(graphId: TaskGraphId): List<Task> =
        listOf(task).filter { it.taskGraphId == graphId }
}

private class FakeRunRecordStore : RunRecordStorePort {
    val saved = mutableListOf<RunRecord>()

    override fun save(runRecord: RunRecord): RunRecord {
        saved += runRecord
        return runRecord
    }

    override fun findById(id: RunId): RunRecord? = saved.firstOrNull { it.id == id }
    override fun findByTaskId(taskId: TaskId): List<RunRecord> = saved.filter { it.taskId == taskId }
}

private class FakeDocumentChunkStore(existingChunk: DocumentChunk) : DocumentChunkStorePort {
    val chunks = mutableListOf(existingChunk)
    val savedBatches = mutableListOf<List<DocumentChunk>>()

    override fun saveAll(chunks: List<DocumentChunk>): List<DocumentChunk> {
        savedBatches += chunks
        this.chunks += chunks
        return chunks
    }

    override fun findByDocumentId(documentId: DocumentId): List<DocumentChunk> =
        chunks.filter { it.documentId == documentId }
}

private class FakeEmbeddingSetStore : EmbeddingSetStorePort {
    val resolved = mutableListOf<EmbeddingSet>()

    override fun resolveOrCreate(embeddingSet: EmbeddingSet): EmbeddingSet {
        resolved += embeddingSet
        return embeddingSet
    }

    override fun findById(id: EmbeddingSetId): EmbeddingSet? = resolved.firstOrNull { it.id == id }
    override fun findByUniqueKey(
        embeddingModel: String,
        embeddingDimension: Int,
        embeddingVersion: String,
        distanceMetric: DistanceMetric,
    ): EmbeddingSet? = resolved.firstOrNull {
        it.embeddingModel == embeddingModel &&
            it.embeddingDimension == embeddingDimension &&
            it.embeddingVersion == embeddingVersion &&
            it.distanceMetric == distanceMetric
    }
}

private class FakeChunkEmbeddingStore : ChunkEmbeddingStorePort {
    val saved = mutableListOf<ChunkEmbedding>()

    override fun saveAll(chunkEmbeddings: List<ChunkEmbedding>): List<ChunkEmbedding> {
        saved += chunkEmbeddings
        return chunkEmbeddings
    }

    override fun findByChunkId(chunkId: DocumentChunkId): List<ChunkEmbedding> =
        saved.filter { it.chunkId == chunkId }
}

private class TestIds {
    val projectId = ProjectId(uuid(1))
    val iterationId = IterationId(uuid(2))
    val documentId = DocumentId(uuid(3))
    val taskGraphId = TaskGraphId(uuid(4))
    val taskId = TaskId(uuid(5))
    val runId = RunId(uuid(6))

    val project = Project(
        id = projectId,
        sourceProjectId = SourceProjectId("source-project"),
        name = "P2A",
        canonicalServerId = CanonicalServerId(projectId.value),
        rootPath = "/repo",
        createdAt = now,
    )
    val iteration = Iteration(
        id = iterationId,
        projectId = projectId,
        sourceIterationId = SourceIterationId("v1"),
        label = "v1",
        status = IterationStatus.ACTIVE,
        createdAt = now,
    )
    val document = DocumentSnapshot(
        id = documentId,
        projectId = projectId,
        iterationId = iterationId,
        sourceDocumentId = SourceDocumentId("source-doc"),
        sourcePath = "spec.md",
        snapshotVersion = 1,
        artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
        title = "Spec",
        content = "existing",
        contentHash = ContentHash("doc-hash"),
        capturedAt = now,
        createdAt = now,
    )
    val taskGraph = TaskGraph(
        id = taskGraphId,
        projectId = projectId,
        iterationId = iterationId,
        sourceTaskGraphId = SourceTaskGraphId("source-graph"),
        sourceDocumentId = SourceDocumentId("source-doc"),
        graphHash = ContentHash("graph-hash"),
        graphJson = "{}",
        createdAt = now,
    )
    val task = Task(
        id = taskId,
        projectId = projectId,
        iterationId = iterationId,
        taskGraphId = taskGraphId,
        sourceTaskId = SourceTaskId("task-1"),
        title = "Task",
        description = "Task",
        status = TaskStatus.READY,
        targetArea = "test",
        createdAt = now,
    )
    val existingChunk = DocumentChunk(
        id = DocumentChunkId(uuid(40)),
        projectId = projectId,
        iterationId = iterationId,
        documentId = documentId,
        artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
        sourcePath = "spec.md",
        chunkIndex = 0,
        content = "existing chunk",
        chunkHash = ContentHash("chunk-existing"),
        createdAt = now,
    )
}

private val now: Instant = Instant.parse("2026-06-29T00:00:00Z")

private fun uuid(index: Int): String =
    "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
