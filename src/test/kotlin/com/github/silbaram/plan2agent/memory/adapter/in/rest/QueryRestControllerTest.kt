package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactsUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.KeywordSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.VectorSearchUseCase
import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.KeywordSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
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
import java.time.Instant

class QueryRestControllerTest {
    private val findArtifacts = FakeFindArtifactsUseCase()
    private val keywordSearch = FakeKeywordSearchUseCase()
    private val vectorSearch = FakeVectorSearchUseCase()
    private val controller = QueryRestController(findArtifacts, keywordSearch, vectorSearch)

    @Test
    fun `artifact lookup maps query params to use case and returns canonical source metadata`() {
        val sourceReference = SourceReference(
            canonicalServerId = CanonicalServerId("local-server"),
            uri = "file:///repo/spec.md",
            path = "spec.md",
        )
        val createdAt = Instant.parse("2026-06-29T01:00:00Z")
        val updatedAt = Instant.parse("2026-06-29T02:00:00Z")
        findArtifacts.result = listOf(
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
            ),
        )
        assertThat(response.single().snapshotVersion).isEqualTo(3)
        assertThat(response.single().createdAt).isEqualTo(createdAt)
        assertThat(response.single().updatedAt).isEqualTo(updatedAt)
        assertThat(response.single().sourceIds.sourceTaskGraphId).isEqualTo("source-graph")
        assertThat(response.single().sourceReference?.path).isEqualTo("spec.md")
        assertThat(response.single().metadata).containsEntry("custom", "value")
    }

    @Test
    fun `keyword search validates q and returns RAG ready match payload`() {
        keywordSearch.result = listOf(
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
            ),
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
            ),
        )
        assertThat(response.single().content).isEqualTo("decision content")
        assertThat(response.single().score).isEqualTo(3.0)
        assertThat(response.single().matchReason).isEqualTo("chunk.content")
        assertThat(response.single().sourceIds.sourceDocumentId).isEqualTo("source-document")

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
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("q is required")
    }

    @Test
    fun `vector search validates embedding request and maps metadata filters`() {
        vectorSearch.result = listOf(
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
            ),
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
            ),
        )
        assertThat(response.single().embeddingModel).isEqualTo("text-embedding-test")
        assertThat(response.single().sourceIds.sourceRunId).isEqualTo("source-run")

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
    fun `health endpoint reports up`() {
        assertThat(HealthRestController().health().status).isEqualTo("UP")
    }
}

private class FakeFindArtifactsUseCase : FindArtifactsUseCase {
    var received: FindArtifactsQuery? = null
    var result: List<ArtifactSummary> = emptyList()

    override fun findArtifacts(query: FindArtifactsQuery): List<ArtifactSummary> {
        received = query
        return result
    }
}

private class FakeKeywordSearchUseCase : KeywordSearchUseCase {
    var received: KeywordSearchQuery? = null
    var result: List<KeywordSearchMatch> = emptyList()

    override fun keywordSearch(query: KeywordSearchQuery): List<KeywordSearchMatch> {
        received = query
        return result
    }
}

private class FakeVectorSearchUseCase : VectorSearchUseCase {
    var received: VectorSearchQuery? = null
    var result: List<VectorSearchMatch> = emptyList()

    override fun vectorSearch(query: VectorSearchQuery): List<VectorSearchMatch> {
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
