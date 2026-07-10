package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactGraphStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactQueryPort
import com.github.silbaram.plan2agent.memory.application.port.out.KeywordSearchPort
import com.github.silbaram.plan2agent.memory.application.port.out.VectorSearchPort
import com.github.silbaram.plan2agent.memory.domain.ArtifactEdge
import com.github.silbaram.plan2agent.memory.domain.ArtifactNode
import com.github.silbaram.plan2agent.memory.domain.ArtifactNodeId
import com.github.silbaram.plan2agent.memory.domain.ArtifactNodeKind
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
import com.github.silbaram.plan2agent.memory.domain.ArtifactTrace
import com.github.silbaram.plan2agent.memory.domain.ArtifactTraceNode
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.KeywordSearchMatch
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.SourceRunId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ReadUseCaseServiceTest {
    private val artifactQuery = FakeArtifactQueryPort()
    private val keywordSearch = FakeKeywordSearchPort()
    private val vectorSearch = FakeVectorSearchPort()
    private val artifactGraph = FakeArtifactGraphStore()
    private val service = ReadUseCaseService(artifactQuery, keywordSearch, vectorSearch, artifactGraph)


    @Test
    fun `graph node search and trace delegate through graph store`() {
        val nodeQuery = GraphNodeSearchQuery(
            projectId = ReadTestIds.projectId,
            iterationId = ReadTestIds.iterationId,
            nodeKind = ArtifactNodeKind.TASK,
            query = "task",
            limit = 5,
        )

        val nodes = service.findGraphNodes(nodeQuery)

        assertThat(artifactGraph.receivedNodeQuery).isEqualTo(nodeQuery)
        assertThat(nodes).containsExactly(artifactGraph.node)

        val traceQuery = GraphTraceQuery(
            projectId = ReadTestIds.projectId,
            iterationId = ReadTestIds.iterationId,
            naturalKey = artifactGraph.node.naturalKey,
            direction = GraphTraceDirection.UPSTREAM,
            maxDepth = 3,
        )

        val trace = service.traceGraph(traceQuery)

        assertThat(artifactGraph.receivedTraceQuery).isEqualTo(traceQuery)
        assertThat(trace.root).isEqualTo(artifactGraph.node)
        assertThat(trace.nodes.single().depth).isZero()
    }

    @Test
    fun `artifact lookup forwards canonical source and relation filters through query port`() {
        val sourceReference = SourceReference(
            canonicalServerId = CanonicalServerId(ReadTestIds.documentId.value),
            uri = "file:///repo/spec.md",
            path = "spec.md",
        )
        val expected = FindArtifactsQuery(
            projectId = ReadTestIds.projectId,
            iterationId = ReadTestIds.iterationId,
            sourceProjectId = SourceProjectId("source-project"),
            sourceIterationId = SourceIterationId("source-iteration"),
            sourceDocumentId = SourceDocumentId("source-document"),
            sourceTaskGraphId = SourceTaskGraphId("source-graph"),
            sourceTaskId = SourceTaskId("source-task"),
            sourceRunId = SourceRunId("source-run"),
            artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
            sourcePath = "spec.md",
            taskId = ReadTestIds.taskId,
            runId = ReadTestIds.runId,
            contentHash = ContentHash("content-hash"),
            sourceReference = sourceReference,
            limit = 25,
        )

        val result = service.findArtifacts(expected)

        assertThat(artifactQuery.received).isEqualTo(expected)
        assertThat(result.items).containsExactly(
            ArtifactSummary(
                artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                artifactId = ReadTestIds.documentId.value,
                projectId = ReadTestIds.projectId,
                iterationId = ReadTestIds.iterationId,
                taskId = ReadTestIds.taskId,
                runId = ReadTestIds.runId,
                sourcePath = "spec.md",
                title = "Spec",
                contentHash = ContentHash("content-hash"),
                sourceReference = sourceReference,
                metadata = mapOf("sourceDocumentId" to "source-document"),
            ),
        )
    }

    @Test
    fun `artifact lookup validates source path before delegating`() {
        assertThatThrownBy {
            service.findArtifacts(FindArtifactsQuery(sourcePath = " "))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sourcePath must not be blank")
        assertThat(artifactQuery.received).isNull()
    }

    @Test
    fun `keyword search validates q limit and filters before delegating`() {
        assertThatThrownBy { KeywordSearchQuery(query = " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("query must not be blank")
        assertThatThrownBy {
            service.keywordSearch(KeywordSearchQuery(query = "rag", sourcePath = " "))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sourcePath must not be blank")
        assertThatThrownBy {
            service.keywordSearch(KeywordSearchQuery(query = "rag", metadataFilters = mapOf(" " to "gate-b")))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("metadata filter keys must not be blank")
        assertThat(keywordSearch.received).isNull()

        val expected = KeywordSearchQuery(
            query = "decision",
            projectId = ReadTestIds.projectId,
            iterationId = ReadTestIds.iterationId,
            artifactType = ArtifactType.DOCUMENT_CHUNK,
            sourcePath = "runs/task.md",
            taskId = ReadTestIds.taskId,
            runId = ReadTestIds.runId,
            metadataFilters = mapOf("phase" to "gate-d"),
            limit = 10,
        )

        val result = service.keywordSearch(expected)

        assertThat(keywordSearch.received).isEqualTo(expected)
        assertThat(result.items.single().metadata).containsEntry("sourceTaskId", "source-task")
    }

    @Test
    fun `vector search validates embedding metadata and dimension before delegating`() {
        assertThatThrownBy {
            service.vectorSearch(
                VectorSearchQuery(
                    embedding = Embedding(listOf(0.1f, 0.2f)),
                    embeddingModel = "text-embedding-test",
                    embeddingDimension = 3,
                    embeddingVersion = "v1",
                    distanceMetric = DistanceMetric.COSINE,
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("embeddingDimension must match embedding size")
        assertThatThrownBy {
            service.vectorSearch(
                VectorSearchQuery(
                    embedding = Embedding(listOf(0.1f, 0.2f)),
                    embeddingModel = " ",
                    embeddingDimension = 2,
                    embeddingVersion = "v1",
                    distanceMetric = DistanceMetric.COSINE,
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("embeddingModel must not be blank")
        assertThatThrownBy {
            service.vectorSearch(
                VectorSearchQuery(
                    embedding = Embedding(listOf(0.1f, 0.2f)),
                    embeddingModel = "text-embedding-test",
                    embeddingDimension = 2,
                    embeddingVersion = " ",
                    distanceMetric = DistanceMetric.COSINE,
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("embeddingVersion must not be blank")
        assertThatThrownBy {
            service.vectorSearch(
                VectorSearchQuery(
                    embedding = Embedding(listOf(Float.NaN)),
                    embeddingModel = "text-embedding-test",
                    embeddingDimension = 1,
                    embeddingVersion = "v1",
                    distanceMetric = DistanceMetric.COSINE,
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("embedding values must be finite")
        assertThat(vectorSearch.received).isNull()

        val expected = VectorSearchQuery(
            embedding = Embedding(listOf(0.1f, 0.2f)),
            embeddingModel = "text-embedding-test",
            embeddingDimension = 2,
            embeddingVersion = "v1",
            distanceMetric = DistanceMetric.COSINE,
            projectId = ReadTestIds.projectId,
            iterationId = ReadTestIds.iterationId,
            artifactType = ArtifactType.DOCUMENT_CHUNK,
            sourcePath = "runs/task.md",
            taskId = ReadTestIds.taskId,
            runId = ReadTestIds.runId,
            metadataFilters = mapOf("phase" to "gate-d"),
            limit = 5,
        )

        val result = service.vectorSearch(expected)

        assertThat(vectorSearch.received).isEqualTo(expected)
        assertThat(result.items.single().embeddingModel).isEqualTo("text-embedding-test")
        assertThat(result.items.single().metadata).containsEntry("sourceRunId", "source-run")
    }

    @Test
    fun `hybrid search fuses keyword and vector candidates with reciprocal rank`() {
        val sharedChunk = DocumentChunkId(uuid(9))
        val keywordOnlyChunk = DocumentChunkId(uuid(10))
        keywordSearch.result = PagedResult(
            items = listOf(
                KeywordSearchMatch(
                    chunkId = sharedChunk,
                    documentId = ReadTestIds.documentId,
                    projectId = ReadTestIds.projectId,
                    iterationId = ReadTestIds.iterationId,
                    artifactType = ArtifactType.DOCUMENT_CHUNK,
                    sourcePath = "runs/shared.md",
                    chunkIndex = 0,
                    content = "shared keyword content",
                    score = 4.0,
                    matchReason = "chunk.content",
                    metadata = mapOf("sourceTaskId" to "source-task"),
                    sourceReference = SourceReference(CanonicalServerId(sharedChunk.value), "file:///repo/runs/shared.md"),
                ),
                KeywordSearchMatch(
                    chunkId = keywordOnlyChunk,
                    documentId = DocumentId(uuid(11)),
                    projectId = ReadTestIds.projectId,
                    iterationId = ReadTestIds.iterationId,
                    artifactType = ArtifactType.DOCUMENT_CHUNK,
                    sourcePath = "runs/keyword.md",
                    chunkIndex = 1,
                    content = "keyword only content",
                    score = 3.0,
                    matchReason = "chunk.content",
                ),
            ),
        )
        vectorSearch.result = PagedResult(
            items = listOf(
                VectorSearchMatch(
                    chunkId = sharedChunk,
                    documentId = ReadTestIds.documentId,
                    projectId = ReadTestIds.projectId,
                    iterationId = ReadTestIds.iterationId,
                    artifactType = ArtifactType.DOCUMENT_CHUNK,
                    sourcePath = "runs/shared.md",
                    chunkIndex = 0,
                    content = "shared vector content",
                    score = 0.1,
                    distanceMetric = DistanceMetric.COSINE,
                    embeddingModel = "text-embedding-test",
                    embeddingVersion = "v1",
                    metadata = mapOf("sourceRunId" to "source-run"),
                ),
            ),
        )
        val query = HybridSearchQuery(
            query = "decision",
            embedding = Embedding(listOf(0.1f, 0.2f)),
            embeddingModel = "text-embedding-test",
            embeddingDimension = 2,
            embeddingVersion = "v1",
            distanceMetric = DistanceMetric.COSINE,
            projectId = ReadTestIds.projectId,
            iterationId = ReadTestIds.iterationId,
            rrfK = 60,
            candidateLimit = 10,
            limit = 10,
        )

        val result = service.hybridSearch(query)

        assertThat(keywordSearch.received?.limit).isEqualTo(10)
        assertThat(keywordSearch.received?.cursor).isNull()
        assertThat(vectorSearch.received?.limit).isEqualTo(10)
        assertThat(vectorSearch.received?.cursor).isNull()
        assertThat(result.items.map { it.chunkId }).containsExactly(sharedChunk, keywordOnlyChunk)
        assertThat(result.items.first().keyword?.rank).isEqualTo(1)
        assertThat(result.items.first().vector?.rank).isEqualTo(1)
        assertThat(result.items.first().matchReason).isEqualTo("hybrid.keyword+vector")
        assertThat(result.items.first().metadata).containsEntry("sourceRunId", "source-run")
        assertThat(result.items.first().metadata).containsEntry("sourceTaskId", "source-task")
        assertThat(result.items.first().sourceReference?.uri).isEqualTo("file:///repo/runs/shared.md")
    }

    @Test
    fun `hybrid search paginates fused candidates with opaque cursor`() {
        val firstChunk = DocumentChunkId(uuid(12))
        val secondChunk = DocumentChunkId(uuid(13))
        val thirdChunk = DocumentChunkId(uuid(14))
        keywordSearch.result = PagedResult(
            items = listOf(
                keywordMatch(firstChunk, score = 3.0),
                keywordMatch(secondChunk, score = 2.0),
                keywordMatch(thirdChunk, score = 1.0),
            ),
        )
        vectorSearch.result = PagedResult(emptyList())
        val query = HybridSearchQuery(
            query = "decision",
            embedding = Embedding(listOf(0.1f, 0.2f)),
            embeddingModel = "text-embedding-test",
            embeddingDimension = 2,
            embeddingVersion = "v1",
            distanceMetric = DistanceMetric.COSINE,
            projectId = ReadTestIds.projectId,
            iterationId = ReadTestIds.iterationId,
            candidateLimit = 3,
            limit = 2,
        )

        val firstPage = service.hybridSearch(query)
        val secondPage = service.hybridSearch(query.copy(cursor = requireNotNull(firstPage.nextCursor)))

        assertThat(firstPage.items.map { it.chunkId }).containsExactly(firstChunk, secondChunk)
        assertThat(firstPage.nextCursor).isNotBlank()
        assertThat(secondPage.items.map { it.chunkId }).containsExactly(thirdChunk)
        assertThat(secondPage.nextCursor).isNull()
    }

    @Test
    fun `hybrid search rejects malformed cursor`() {
        val query = HybridSearchQuery(
            query = "decision",
            embedding = Embedding(listOf(0.1f, 0.2f)),
            embeddingModel = "text-embedding-test",
            embeddingDimension = 2,
            embeddingVersion = "v1",
            distanceMetric = DistanceMetric.COSINE,
            candidateLimit = 3,
            limit = 2,
            cursor = "not-a-cursor",
        )

        assertThatThrownBy { service.hybridSearch(query) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cursor has invalid format")
    }
}

private fun keywordMatch(
    chunkId: DocumentChunkId,
    score: Double,
): KeywordSearchMatch =
    KeywordSearchMatch(
        chunkId = chunkId,
        documentId = ReadTestIds.documentId,
        projectId = ReadTestIds.projectId,
        iterationId = ReadTestIds.iterationId,
        artifactType = ArtifactType.DOCUMENT_CHUNK,
        sourcePath = "runs/${chunkId.value}.md",
        chunkIndex = 0,
        content = "keyword content ${chunkId.value}",
        score = score,
        matchReason = "chunk.content",
    )

private class FakeArtifactGraphStore : ArtifactGraphStorePort {
    val node = ArtifactNode(
        id = ArtifactNodeId(uuid(20)),
        projectId = ReadTestIds.projectId,
        iterationId = ReadTestIds.iterationId,
        kind = ArtifactNodeKind.TASK,
        naturalKey = "task:T-1",
        label = "Task T-1",
    )
    var receivedNodeQuery: GraphNodeSearchQuery? = null
    var receivedTraceQuery: GraphTraceQuery? = null

    override fun replaceSnapshot(
        projectId: ProjectId,
        iterationId: IterationId?,
        nodes: List<ArtifactNode>,
        edges: List<ArtifactEdge>,
    ): ArtifactGraphSnapshotResult = error("not used")

    override fun findNodes(query: GraphNodeSearchQuery): List<ArtifactNode> {
        receivedNodeQuery = query
        return listOf(node)
    }

    override fun trace(query: GraphTraceQuery): ArtifactTrace {
        receivedTraceQuery = query
        return ArtifactTrace(node, listOf(ArtifactTraceNode(node, 0)), emptyList(), truncated = false)
    }
}

private class FakeArtifactQueryPort : ArtifactQueryPort {
    var received: FindArtifactsQuery? = null

    override fun findArtifacts(query: FindArtifactsQuery): PagedResult<ArtifactSummary> {
        received = query
        return PagedResult(
            items = listOf(
                ArtifactSummary(
                    artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                    artifactId = ReadTestIds.documentId.value,
                    projectId = ReadTestIds.projectId,
                    iterationId = ReadTestIds.iterationId,
                    taskId = ReadTestIds.taskId,
                    runId = ReadTestIds.runId,
                    sourcePath = "spec.md",
                    title = "Spec",
                    contentHash = ContentHash("content-hash"),
                    sourceReference = query.sourceReference,
                    metadata = mapOf("sourceDocumentId" to "source-document"),
                ),
            ),
        )
    }
}

private class FakeKeywordSearchPort : KeywordSearchPort {
    var received: KeywordSearchQuery? = null
    var result: PagedResult<KeywordSearchMatch>? = null

    override fun search(query: KeywordSearchQuery): PagedResult<KeywordSearchMatch> {
        received = query
        return result ?: PagedResult(
            items = listOf(
                KeywordSearchMatch(
                    chunkId = DocumentChunkId(uuid(7)),
                    documentId = ReadTestIds.documentId,
                    projectId = ReadTestIds.projectId,
                    iterationId = ReadTestIds.iterationId,
                    artifactType = ArtifactType.DOCUMENT_CHUNK,
                    sourcePath = query.sourcePath,
                    chunkIndex = 0,
                    content = "decision content",
                    score = 1.0,
                    matchReason = "content",
                    metadata = mapOf("sourceTaskId" to "source-task"),
                ),
            ),
        )
    }
}

private class FakeVectorSearchPort : VectorSearchPort {
    var received: VectorSearchQuery? = null
    var result: PagedResult<VectorSearchMatch>? = null

    override fun search(query: VectorSearchQuery): PagedResult<VectorSearchMatch> {
        received = query
        return result ?: PagedResult(
            items = listOf(
                VectorSearchMatch(
                    chunkId = DocumentChunkId(uuid(8)),
                    documentId = ReadTestIds.documentId,
                    projectId = ReadTestIds.projectId,
                    iterationId = ReadTestIds.iterationId,
                    artifactType = ArtifactType.DOCUMENT_CHUNK,
                    sourcePath = query.sourcePath,
                    chunkIndex = 1,
                    content = "similar content",
                    score = 0.2,
                    distanceMetric = query.distanceMetric,
                    embeddingModel = query.embeddingModel,
                    embeddingVersion = query.embeddingVersion,
                    metadata = mapOf("sourceRunId" to "source-run"),
                ),
            ),
        )
    }
}

private data object ReadTestIds {
    val projectId = ProjectId(uuid(1))
    val iterationId = IterationId(uuid(2))
    val documentId = DocumentId(uuid(3))
    val taskId = TaskId(uuid(4))
    val runId = RunId(uuid(5))
}

private fun uuid(index: Int): String =
    "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
