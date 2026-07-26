package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveVectorSearchQuery
import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactGraphStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactQueryPort
import com.github.silbaram.plan2agent.memory.application.port.out.KeywordSearchPort
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderUnavailableException
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
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
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
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch
import com.github.silbaram.plan2agent.memory.support.FakeEmbeddingFailure
import com.github.silbaram.plan2agent.memory.support.FakeEmbeddingMode
import com.github.silbaram.plan2agent.memory.support.FakeEmbeddingPort
import com.github.silbaram.plan2agent.memory.support.FakeEmbeddingRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ReadUseCaseServiceTest {
    private val artifactQuery = FakeArtifactQueryPort()
    private val keywordSearch = FakeKeywordSearchPort()
    private val vectorSearch = FakeVectorSearchPort()
    private val embeddingPort = FakeEmbeddingPort(
        activeEmbeddingTarget = ActiveEmbeddingTarget(
            profile = V2EmbeddingProfile.fixed,
            embeddingSetId = EmbeddingSetId("active-v2-set"),
        ),
    )
    private val semanticSearch = SemanticSearchUseCaseService(embeddingPort, vectorSearch)
    private val artifactGraph = FakeReadArtifactGraphStore()
    private val service = ReadUseCaseService(artifactQuery, keywordSearch, vectorSearch, semanticSearch, artifactGraph)


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
    fun `artifact lookup normalizes dashboard artifact type sets and rejects unsupported filters`() {
        val query = FindArtifactsQuery(
            artifactTypes = linkedSetOf(
                ArtifactType.TASK,
                ArtifactType.DOCUMENT_SNAPSHOT,
                ArtifactType.RUN_RECORD,
            ),
        )

        assertThat(query.normalizedArtifactTypes).containsExactly(
            ArtifactType.DOCUMENT_SNAPSHOT,
            ArtifactType.RUN_RECORD,
            ArtifactType.TASK,
        )
        assertThat(FindArtifactsQuery(artifactType = ArtifactType.TASK).normalizedArtifactTypes)
            .containsExactly(ArtifactType.TASK)

        assertThatThrownBy {
            FindArtifactsQuery(
                artifactType = ArtifactType.TASK,
                artifactTypes = setOf(ArtifactType.RUN_RECORD),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not be supplied together")
        assertThatThrownBy { FindArtifactsQuery(artifactTypes = emptySet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not be empty")
        listOf(ArtifactType.DOCUMENT_CHUNK, ArtifactType.PROJECT, ArtifactType.ITERATION).forEach { unsupported ->
            assertThatThrownBy { FindArtifactsQuery(artifactTypes = setOf(unsupported)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("not supported for artifact listing")
        }
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
    fun `hybrid search uses the server managed semantic arm with every filter and default fusion`() {
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
        vectorSearch.activeResult = PagedResult(
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
            projectId = ReadTestIds.projectId,
            iterationId = ReadTestIds.iterationId,
            artifactType = ArtifactType.DOCUMENT_CHUNK,
            sourcePath = "runs/shared.md",
            taskId = ReadTestIds.taskId,
            runId = ReadTestIds.runId,
            metadataFilters = mapOf("phase" to "gate-d"),
        )

        val result = service.hybridSearch(query)

        assertThat(embeddingPort.requests).containsExactly(FakeEmbeddingRequest(FakeEmbeddingMode.QUERY, "decision"))
        assertThat(keywordSearch.received).isEqualTo(
            KeywordSearchQuery(
                query = "decision",
                projectId = ReadTestIds.projectId,
                iterationId = ReadTestIds.iterationId,
                artifactType = ArtifactType.DOCUMENT_CHUNK,
                sourcePath = "runs/shared.md",
                taskId = ReadTestIds.taskId,
                runId = ReadTestIds.runId,
                metadataFilters = mapOf("phase" to "gate-d"),
                limit = DEFAULT_HYBRID_CANDIDATE_LIMIT,
            ),
        )
        assertThat(keywordSearch.received?.cursor).isNull()
        assertThat(vectorSearch.activeQuery).isEqualTo(
            ActiveVectorSearchQuery(
                embeddingSetId = EmbeddingSetId("active-v2-set"),
                embedding = requireNotNull(vectorSearch.activeQuery).embedding,
                projectId = ReadTestIds.projectId,
                iterationId = ReadTestIds.iterationId,
                artifactType = ArtifactType.DOCUMENT_CHUNK,
                sourcePath = "runs/shared.md",
                taskId = ReadTestIds.taskId,
                runId = ReadTestIds.runId,
                metadataFilters = mapOf("phase" to "gate-d"),
                limit = DEFAULT_HYBRID_CANDIDATE_LIMIT,
            ),
        )
        assertThat(vectorSearch.activeQuery?.cursor).isNull()
        assertThat(result.items.map { it.chunkId }).containsExactly(sharedChunk, keywordOnlyChunk)
        assertThat(result.items.first().keyword?.rank).isEqualTo(1)
        assertThat(result.items.first().vector?.rank).isEqualTo(1)
        assertThat(result.items.first().matchReason).isEqualTo("hybrid.keyword+vector")
        assertThat(result.items.first().metadata).containsEntry("sourceRunId", "source-run")
        assertThat(result.items.first().metadata).containsEntry("sourceTaskId", "source-task")
        assertThat(result.items.first().sourceReference?.uri).isEqualTo("file:///repo/runs/shared.md")
        assertThat(result.items.first().score).isEqualTo(2.0 / (DEFAULT_RRF_K + 1).toDouble())
    }

    @Test
    fun `hybrid search returns keyword only matches when semantic search has no candidates`() {
        val firstChunk = DocumentChunkId(uuid(12))
        keywordSearch.result = PagedResult(
            items = listOf(
                keywordMatch(firstChunk, score = 3.0),
            ),
        )
        vectorSearch.activeResult = PagedResult(emptyList())

        val result = service.hybridSearch(HybridSearchQuery(query = "decision", candidateLimit = 3, limit = 2))

        assertThat(result.items.single().chunkId).isEqualTo(firstChunk)
        assertThat(result.items.single().matchReason).isEqualTo("hybrid.keyword")
        assertThat(result.items.single().vector).isNull()
    }

    @Test
    fun `hybrid search returns semantic only matches and propagates provider failures`() {
        val semanticChunk = DocumentChunkId(uuid(13))
        keywordSearch.result = PagedResult(emptyList())
        vectorSearch.activeResult = PagedResult(items = listOf(vectorMatch(semanticChunk, score = 0.1)))

        val semanticOnly = service.hybridSearch(HybridSearchQuery(query = "decision", candidateLimit = 3, limit = 2))

        assertThat(semanticOnly.items.single().chunkId).isEqualTo(semanticChunk)
        assertThat(semanticOnly.items.single().matchReason).isEqualTo("hybrid.vector")
        assertThat(semanticOnly.items.single().keyword).isNull()

        embeddingPort.fail(FakeEmbeddingMode.QUERY, FakeEmbeddingFailure.RETRYABLE_UNAVAILABLE)
        keywordSearch.received = null

        assertThatThrownBy { service.hybridSearch(HybridSearchQuery(query = "provider failure")) }
            .isInstanceOf(ProviderUnavailableException::class.java)
            .hasMessageContaining("Embedding provider is unavailable")
        assertThat(keywordSearch.received).isNull()
    }

    @Test
    fun `hybrid search paginates fused candidates with an opaque cursor`() {
        val firstChunk = DocumentChunkId(uuid(14))
        val secondChunk = DocumentChunkId(uuid(15))
        val thirdChunk = DocumentChunkId(uuid(16))
        keywordSearch.result = PagedResult(
            items = listOf(
                keywordMatch(firstChunk, score = 3.0),
                keywordMatch(secondChunk, score = 2.0),
                keywordMatch(thirdChunk, score = 1.0),
            ),
        )
        vectorSearch.activeResult = PagedResult(emptyList())
        val query = HybridSearchQuery(query = "decision", candidateLimit = 3, limit = 2)

        val firstPage = service.hybridSearch(query)
        val secondPage = service.hybridSearch(query.copy(cursor = requireNotNull(firstPage.nextCursor)))

        assertThat(firstPage.items.map { it.chunkId }).containsExactly(firstChunk, secondChunk)
        assertThat(firstPage.nextCursor).isNotBlank()
        assertThat(secondPage.items.map { it.chunkId }).containsExactly(thirdChunk)
        assertThat(secondPage.nextCursor).isNull()
        assertThat(vectorSearch.activeQuery?.limit).isEqualTo(3)
        assertThat(vectorSearch.activeQuery?.cursor).isNull()
    }

    @Test
    fun `hybrid cursor permits page limit and metadata ordering changes but rejects fusion changes`() {
        val firstChunk = DocumentChunkId(uuid(17))
        val secondChunk = DocumentChunkId(uuid(18))
        val thirdChunk = DocumentChunkId(uuid(19))
        keywordSearch.result = PagedResult(
            items = listOf(
                keywordMatch(firstChunk, score = 3.0),
                keywordMatch(secondChunk, score = 2.0),
                keywordMatch(thirdChunk, score = 1.0),
            ),
        )
        vectorSearch.activeResult = PagedResult(emptyList())
        val query = HybridSearchQuery(
            query = "decision",
            metadataFilters = linkedMapOf("phase" to "gate-d", "owner" to "memory"),
            candidateLimit = 3,
            limit = 1,
        )

        val firstPage = service.hybridSearch(query)
        val secondPage = service.hybridSearch(
            query.copy(
                metadataFilters = linkedMapOf("owner" to "memory", "phase" to "gate-d"),
                limit = 2,
                cursor = requireNotNull(firstPage.nextCursor),
            ),
        )

        assertThat(secondPage.items.map { it.chunkId }).containsExactly(secondChunk, thirdChunk)
        assertThatThrownBy {
            service.hybridSearch(query.copy(rrfK = 61, cursor = requireNotNull(firstPage.nextCursor)))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("cursor does not match this request")
        assertThatThrownBy {
            service.hybridSearch(query.copy(candidateLimit = 4, cursor = requireNotNull(firstPage.nextCursor)))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("cursor does not match this request")
    }

    @Test
    fun `hybrid search rejects malformed cursor`() {
        assertThatThrownBy {
            service.hybridSearch(
                HybridSearchQuery(query = "decision", candidateLimit = 3, limit = 2, cursor = "not-a-cursor"),
            )
        }
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

private fun vectorMatch(
    chunkId: DocumentChunkId,
    score: Double,
): VectorSearchMatch =
    VectorSearchMatch(
        chunkId = chunkId,
        documentId = ReadTestIds.documentId,
        projectId = ReadTestIds.projectId,
        iterationId = ReadTestIds.iterationId,
        artifactType = ArtifactType.DOCUMENT_CHUNK,
        sourcePath = "runs/${chunkId.value}.md",
        chunkIndex = 0,
        content = "semantic content ${chunkId.value}",
        score = score,
        distanceMetric = DistanceMetric.COSINE,
        embeddingModel = V2EmbeddingProfile.MODEL,
        embeddingVersion = V2EmbeddingProfile.REVISION,
    )

private class FakeReadArtifactGraphStore : ArtifactGraphStorePort {
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
    var activeQuery: ActiveVectorSearchQuery? = null
    var activeResult: PagedResult<VectorSearchMatch>? = null

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

    override fun search(query: ActiveVectorSearchQuery): PagedResult<VectorSearchMatch> {
        activeQuery = query
        return activeResult ?: PagedResult(emptyList())
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
