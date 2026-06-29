package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactQueryPort
import com.github.silbaram.plan2agent.memory.application.port.out.KeywordSearchPort
import com.github.silbaram.plan2agent.memory.application.port.out.VectorSearchPort
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

class ReadUseCaseServiceTest {
    private val artifactQuery = FakeArtifactQueryPort()
    private val keywordSearch = FakeKeywordSearchPort()
    private val vectorSearch = FakeVectorSearchPort()
    private val service = ReadUseCaseService(artifactQuery, keywordSearch, vectorSearch)

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
        assertThat(result).containsExactly(
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
        assertThat(result.single().metadata).containsEntry("sourceTaskId", "source-task")
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
        assertThat(result.single().embeddingModel).isEqualTo("text-embedding-test")
        assertThat(result.single().metadata).containsEntry("sourceRunId", "source-run")
    }
}

private class FakeArtifactQueryPort : ArtifactQueryPort {
    var received: FindArtifactsQuery? = null

    override fun findArtifacts(query: FindArtifactsQuery): List<ArtifactSummary> {
        received = query
        return listOf(
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
        )
    }
}

private class FakeKeywordSearchPort : KeywordSearchPort {
    var received: KeywordSearchQuery? = null

    override fun search(query: KeywordSearchQuery): List<KeywordSearchMatch> {
        received = query
        return listOf(
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
        )
    }
}

private class FakeVectorSearchPort : VectorSearchPort {
    var received: VectorSearchQuery? = null

    override fun search(query: VectorSearchQuery): List<VectorSearchMatch> {
        received = query
        return listOf(
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
