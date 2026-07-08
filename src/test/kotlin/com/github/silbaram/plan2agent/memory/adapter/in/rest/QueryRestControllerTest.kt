package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactsUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.HybridSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.KeywordSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.VectorSearchUseCase
import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.HybridSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.KeywordSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.HybridSearchArm
import com.github.silbaram.plan2agent.memory.domain.HybridSearchMatch
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
import java.time.Instant

class QueryRestControllerTest {
    private val findArtifacts = FakeFindArtifactsUseCase()
    private val keywordSearch = FakeKeywordSearchUseCase()
    private val vectorSearch = FakeVectorSearchUseCase()
    private val hybridSearch = FakeHybridSearchUseCase()
    private val controller = QueryRestController(findArtifacts, keywordSearch, vectorSearch, hybridSearch)

    @Test
    fun `artifact lookup maps query params to use case and returns canonical source metadata`() {
        val sourceReference = SourceReference(
            canonicalServerId = CanonicalServerId("local-server"),
            uri = "file:///repo/spec.md",
            path = "spec.md",
        )
        val createdAt = Instant.parse("2026-06-29T01:00:00Z")
        val updatedAt = Instant.parse("2026-06-29T02:00:00Z")
        findArtifacts.result = PagedResult(
            items = listOf(
                ArtifactSummary(
                    artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                    artifactId = RestTestIds.documentId.value,
                    projectId = RestTestIds.projectId,
                    iterationId = RestTestIds.iterationId,
                    taskId = RestTestIds.taskId,
                    runId = RestTestIds.runId,
                    sourcePath = "spec.md",
                    title = "Spec",
                    contentHash = ContentHash("content-hash"),
                    sourceReference = sourceReference,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    metadata = mapOf(
                        "sourceProjectId" to "source-project",
                        "sourceIterationId" to "source-iteration",
                        "sourceDocumentId" to "source-document",
                        "sourceTaskGraphId" to "source-graph",
                        "sourceTaskId" to "source-task",
                        "sourceRunId" to "source-run",
                        "snapshotVersion" to "3",
                        "custom" to "value",
                    ),
                ),
            ),
            nextCursor = "next-artifact-cursor",
        )

        val response = controller.findArtifacts(
            projectId = RestTestIds.projectId.value,
            iterationId = RestTestIds.iterationId.value,
            sourceProjectId = "source-project",
            sourceIterationId = "source-iteration",
            sourceDocumentId = "source-document",
            sourceTaskGraphId = "source-graph",
            sourceTaskId = "source-task",
            sourceRunId = "source-run",
            artifactType = "document_snapshot",
            sourcePath = "spec.md",
            taskId = RestTestIds.taskId.value,
            runId = RestTestIds.runId.value,
            contentHash = "content-hash",
            sourceReferenceCanonicalServerId = "local-server",
            sourceReferenceUri = "file:///repo/spec.md",
            limit = 25,
            cursor = "artifact-cursor",
        )

        assertThat(findArtifacts.received).isEqualTo(
            FindArtifactsQuery(
                projectId = RestTestIds.projectId,
                iterationId = RestTestIds.iterationId,
                sourceProjectId = SourceProjectId("source-project"),
                sourceIterationId = SourceIterationId("source-iteration"),
                sourceDocumentId = SourceDocumentId("source-document"),
                sourceTaskGraphId = SourceTaskGraphId("source-graph"),
                sourceTaskId = SourceTaskId("source-task"),
                sourceRunId = SourceRunId("source-run"),
                artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                sourcePath = "spec.md",
                taskId = RestTestIds.taskId,
                runId = RestTestIds.runId,
                contentHash = ContentHash("content-hash"),
                sourceReference = SourceReference(CanonicalServerId("local-server"), "file:///repo/spec.md"),
                limit = 25,
                cursor = "artifact-cursor",
            ),
        )
        assertThat(response.items.single().snapshotVersion).isEqualTo(3)
        assertThat(response.items.single().createdAt).isEqualTo(createdAt)
        assertThat(response.items.single().updatedAt).isEqualTo(updatedAt)
        assertThat(response.items.single().sourceIds.sourceTaskGraphId).isEqualTo("source-graph")
        assertThat(response.items.single().sourceReference?.path).isEqualTo("spec.md")
        assertThat(response.items.single().metadata).containsEntry("custom", "value")
        assertThat(response.nextCursor).isEqualTo("next-artifact-cursor")
    }

    @Test
    fun `keyword search validates q and returns RAG ready match payload`() {
        keywordSearch.result = PagedResult(
            items = listOf(
                KeywordSearchMatch(
                    chunkId = RestTestIds.chunkId,
                    documentId = RestTestIds.documentId,
                    projectId = RestTestIds.projectId,
                    iterationId = RestTestIds.iterationId,
                    artifactType = ArtifactType.DOCUMENT_CHUNK,
                    sourcePath = "runs/task.md",
                    chunkIndex = 2,
                    content = "decision content",
                    score = 3.0,
                    matchReason = "chunk.content",
                    metadata = mapOf("sourceDocumentId" to "source-document", "sourceTaskId" to "source-task"),
                    sourceReference = SourceReference(CanonicalServerId(RestTestIds.chunkId.value), "file:///repo/runs/task.md"),
                ),
            ),
            nextCursor = "next-keyword-cursor",
        )

        val response = controller.keywordSearch(
            q = " decision ",
            projectId = RestTestIds.projectId.value,
            iterationId = RestTestIds.iterationId.value,
            artifactType = "document_chunk",
            sourcePath = "runs/task.md",
            taskId = RestTestIds.taskId.value,
            runId = RestTestIds.runId.value,
            limit = 10,
            cursor = "keyword-cursor",
        )

        assertThat(keywordSearch.received).isEqualTo(
            KeywordSearchQuery(
                query = "decision",
                projectId = RestTestIds.projectId,
                iterationId = RestTestIds.iterationId,
                artifactType = ArtifactType.DOCUMENT_CHUNK,
                sourcePath = "runs/task.md",
                taskId = RestTestIds.taskId,
                runId = RestTestIds.runId,
                limit = 10,
                cursor = "keyword-cursor",
            ),
        )
        assertThat(response.items.single().content).isEqualTo("decision content")
        assertThat(response.items.single().score).isEqualTo(3.0)
        assertThat(response.items.single().matchReason).isEqualTo("chunk.content")
        assertThat(response.items.single().sourceIds.sourceDocumentId).isEqualTo("source-document")
        assertThat(response.items.single().citation.sourceReference?.uri).isEqualTo("file:///repo/runs/task.md")
        assertThat(response.nextCursor).isEqualTo("next-keyword-cursor")

        assertThatThrownBy {
            controller.keywordSearch(
                q = " ",
                projectId = null,
                iterationId = null,
                artifactType = null,
                sourcePath = null,
                taskId = null,
                runId = null,
                limit = null,
                cursor = null,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("q is required")

        assertThatThrownBy {
            controller.keywordSearch(
                q = "decision",
                projectId = null,
                iterationId = null,
                artifactType = "not_an_artifact",
                sourcePath = null,
                taskId = null,
                runId = null,
                limit = null,
                cursor = null,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("artifactType has invalid value")
    }

    @Test
    fun `artifact lookup accepts proposal artifact type`() {
        controller.findArtifacts(
            projectId = null,
            iterationId = null,
            sourceProjectId = null,
            sourceIterationId = null,
            sourceDocumentId = null,
            sourceTaskGraphId = null,
            sourceTaskId = null,
            sourceRunId = null,
            artifactType = "proposal",
            sourcePath = ".plan2agent/proposals/proposal-run-123-harness-gap.json",
            taskId = null,
            runId = null,
            contentHash = null,
            sourceReferenceCanonicalServerId = null,
            sourceReferenceUri = null,
            limit = 10,
            cursor = null,
        )

        assertThat(findArtifacts.received?.artifactType).isEqualTo(ArtifactType.PROPOSAL)
        assertThat(findArtifacts.received?.sourcePath).isEqualTo(".plan2agent/proposals/proposal-run-123-harness-gap.json")
    }

    @Test
    fun `vector search validates embedding request and maps metadata filters`() {
        vectorSearch.result = PagedResult(
            items = listOf(
                VectorSearchMatch(
                    chunkId = RestTestIds.chunkId,
                    documentId = RestTestIds.documentId,
                    projectId = RestTestIds.projectId,
                    iterationId = RestTestIds.iterationId,
                    artifactType = ArtifactType.DOCUMENT_CHUNK,
                    sourcePath = "runs/task.md",
                    chunkIndex = 1,
                    content = "similar content",
                    score = 0.12,
                    distanceMetric = DistanceMetric.COSINE,
                    embeddingModel = "text-embedding-test",
                    embeddingVersion = "v1",
                    metadata = mapOf("sourceRunId" to "source-run", "sourceChunkId" to "source-chunk"),
                    sourceReference = SourceReference(CanonicalServerId(RestTestIds.chunkId.value), "file:///repo/runs/task.md"),
                ),
            ),
            nextCursor = "next-vector-cursor",
        )

        val response = controller.vectorSearch(
            VectorSearchRequest(
                embedding = listOf(0.1f, 0.2f),
                embeddingModel = "text-embedding-test",
                embeddingDimension = 2,
                embeddingVersion = "v1",
                distanceMetric = "cosine",
                projectId = RestTestIds.projectId.value,
                iterationId = RestTestIds.iterationId.value,
                artifactType = "document_chunk",
                sourcePath = "runs/task.md",
                taskId = RestTestIds.taskId.value,
                runId = RestTestIds.runId.value,
                metadataFilters = mapOf("kind" to "gate-d"),
                limit = 5,
                cursor = "vector-cursor",
            ),
        )

        assertThat(vectorSearch.received).isEqualTo(
            VectorSearchQuery(
                embedding = Embedding(listOf(0.1f, 0.2f)),
                embeddingModel = "text-embedding-test",
                embeddingDimension = 2,
                embeddingVersion = "v1",
                distanceMetric = DistanceMetric.COSINE,
                projectId = RestTestIds.projectId,
                iterationId = RestTestIds.iterationId,
                artifactType = ArtifactType.DOCUMENT_CHUNK,
                sourcePath = "runs/task.md",
                taskId = RestTestIds.taskId,
                runId = RestTestIds.runId,
                metadataFilters = mapOf("kind" to "gate-d"),
                limit = 5,
                cursor = "vector-cursor",
            ),
        )
        assertThat(response.items.single().embeddingModel).isEqualTo("text-embedding-test")
        assertThat(response.items.single().sourceIds.sourceRunId).isEqualTo("source-run")
        assertThat(response.items.single().citation.sourceReference?.uri).isEqualTo("file:///repo/runs/task.md")
        assertThat(response.nextCursor).isEqualTo("next-vector-cursor")

        assertThatThrownBy {
            controller.vectorSearch(
                VectorSearchRequest(
                    embedding = listOf(0.1f, 0.2f),
                    embeddingModel = "text-embedding-test",
                    embeddingDimension = 3,
                    embeddingVersion = "v1",
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("embeddingDimension must match embedding size")
    }

    @Test
    fun `hybrid search maps request to use case and returns fused arm scores with citation`() {
        hybridSearch.result = PagedResult(
            items = listOf(
                HybridSearchMatch(
                    chunkId = RestTestIds.chunkId,
                    documentId = RestTestIds.documentId,
                    projectId = RestTestIds.projectId,
                    iterationId = RestTestIds.iterationId,
                    artifactType = ArtifactType.DOCUMENT_CHUNK,
                    sourcePath = "runs/task.md",
                    chunkIndex = 0,
                    content = "hybrid content",
                    score = 0.032,
                    matchReason = "hybrid.keyword+vector",
                    keyword = HybridSearchArm(rank = 1, score = 4.0),
                    vector = HybridSearchArm(rank = 2, score = 0.1),
                    metadata = mapOf("sourceDocumentId" to "source-document", "sourceRunId" to "source-run"),
                    sourceReference = SourceReference(CanonicalServerId(RestTestIds.chunkId.value), "file:///repo/runs/task.md"),
                ),
            ),
            nextCursor = "next-hybrid-cursor",
        )

        val response = controller.hybridSearch(
            HybridSearchRequest(
                q = "decision",
                embedding = listOf(0.1f, 0.2f),
                embeddingModel = "text-embedding-test",
                embeddingDimension = 2,
                embeddingVersion = "v1",
                distanceMetric = "cosine",
                projectId = RestTestIds.projectId.value,
                iterationId = RestTestIds.iterationId.value,
                artifactType = "document_chunk",
                sourcePath = "runs/task.md",
                taskId = RestTestIds.taskId.value,
                runId = RestTestIds.runId.value,
                metadataFilters = mapOf("kind" to "gate-d"),
                rrfK = 60,
                candidateLimit = 12,
                limit = 5,
                cursor = "hybrid-cursor",
            ),
        )

        assertThat(hybridSearch.received).isEqualTo(
            HybridSearchQuery(
                query = "decision",
                embedding = Embedding(listOf(0.1f, 0.2f)),
                embeddingModel = "text-embedding-test",
                embeddingDimension = 2,
                embeddingVersion = "v1",
                distanceMetric = DistanceMetric.COSINE,
                projectId = RestTestIds.projectId,
                iterationId = RestTestIds.iterationId,
                artifactType = ArtifactType.DOCUMENT_CHUNK,
                sourcePath = "runs/task.md",
                taskId = RestTestIds.taskId,
                runId = RestTestIds.runId,
                metadataFilters = mapOf("kind" to "gate-d"),
                rrfK = 60,
                candidateLimit = 12,
                limit = 5,
                cursor = "hybrid-cursor",
            ),
        )
        assertThat(response.items.single().matchReason).isEqualTo("hybrid.keyword+vector")
        assertThat(response.items.single().keyword?.rank).isEqualTo(1)
        assertThat(response.items.single().vector?.rank).isEqualTo(2)
        assertThat(response.items.single().citation.sourceReference?.uri).isEqualTo("file:///repo/runs/task.md")
        assertThat(response.nextCursor).isEqualTo("next-hybrid-cursor")
    }

    @Test
    fun `health endpoint reports up`() {
        assertThat(HealthRestController().health().status).isEqualTo("UP")
    }
}

private class FakeFindArtifactsUseCase : FindArtifactsUseCase {
    var received: FindArtifactsQuery? = null
    var result: PagedResult<ArtifactSummary> = PagedResult(emptyList(), nextCursor = "next-artifact-cursor")

    override fun findArtifacts(query: FindArtifactsQuery): PagedResult<ArtifactSummary> {
        received = query
        return result
    }
}

private class FakeKeywordSearchUseCase : KeywordSearchUseCase {
    var received: KeywordSearchQuery? = null
    var result: PagedResult<KeywordSearchMatch> = PagedResult(emptyList(), nextCursor = "next-keyword-cursor")

    override fun keywordSearch(query: KeywordSearchQuery): PagedResult<KeywordSearchMatch> {
        received = query
        return result
    }
}

private class FakeVectorSearchUseCase : VectorSearchUseCase {
    var received: VectorSearchQuery? = null
    var result: PagedResult<VectorSearchMatch> = PagedResult(emptyList(), nextCursor = "next-vector-cursor")

    override fun vectorSearch(query: VectorSearchQuery): PagedResult<VectorSearchMatch> {
        received = query
        return result
    }
}

private class FakeHybridSearchUseCase : HybridSearchUseCase {
    var received: HybridSearchQuery? = null
    var result: PagedResult<HybridSearchMatch> = PagedResult(emptyList(), nextCursor = "next-hybrid-cursor")

    override fun hybridSearch(query: HybridSearchQuery): PagedResult<HybridSearchMatch> {
        received = query
        return result
    }
}

private data object RestTestIds {
    val projectId = ProjectId(uuid(1))
    val iterationId = IterationId(uuid(2))
    val documentId = DocumentId(uuid(3))
    val taskId = TaskId(uuid(4))
    val runId = RunId(uuid(5))
    val chunkId = DocumentChunkId(uuid(6))
}

private fun uuid(index: Int): String =
    "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
