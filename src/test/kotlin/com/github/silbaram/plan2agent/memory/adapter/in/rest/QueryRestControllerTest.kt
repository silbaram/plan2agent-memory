@file:Suppress("DEPRECATION")

package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.github.silbaram.plan2agent.memory.config.JacksonObjectMapperConfig
import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactsUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactGraphNodesUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.HybridSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.KeywordSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.SemanticSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.TraceArtifactGraphUseCase
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveVectorSearchQuery
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderUnavailableException
import com.github.silbaram.plan2agent.memory.application.port.out.VectorSearchPort
import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.DEFAULT_RRF_K
import com.github.silbaram.plan2agent.memory.application.usecase.GraphNodeSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.GraphTraceQuery
import com.github.silbaram.plan2agent.memory.application.usecase.HybridSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.KeywordSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.SemanticSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.SemanticSearchUseCaseService
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
import com.github.silbaram.plan2agent.memory.domain.ArtifactNode
import com.github.silbaram.plan2agent.memory.domain.ArtifactTrace
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
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
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch
import com.github.silbaram.plan2agent.memory.support.FakeEmbeddingPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class QueryRestControllerTest {
    private val findArtifacts = FakeFindArtifactsUseCase()
    private val keywordSearch = FakeKeywordSearchUseCase()
    private val semanticSearch = FakeSemanticSearchUseCase()
    private val hybridSearch = FakeHybridSearchUseCase()
    private val findGraphNodes = FakeFindArtifactGraphNodesUseCase()
    private val traceGraph = FakeTraceArtifactGraphUseCase()
    private val controller = QueryRestController(
        findArtifactsUseCase = findArtifacts,
        keywordSearchUseCase = keywordSearch,
        semanticSearchUseCase = semanticSearch,
        hybridSearchUseCase = hybridSearch,
        findArtifactGraphNodesUseCase = findGraphNodes,
        traceArtifactGraphUseCase = traceGraph,
    )

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
    fun `semantic search accepts text and filters only then preserves vector citation response`() {
        semanticSearch.result = PagedResult(
            items = listOf(
                VectorSearchMatch(
                    chunkId = RestTestIds.chunkId,
                    documentId = RestTestIds.documentId,
                    projectId = RestTestIds.projectId,
                    iterationId = RestTestIds.iterationId,
                    artifactType = ArtifactType.DOCUMENT_CHUNK,
                    sourcePath = "runs/task.md",
                    chunkIndex = 1,
                    content = "semantic content",
                    score = 0.12,
                    distanceMetric = DistanceMetric.COSINE,
                    embeddingModel = "intfloat/multilingual-e5-small",
                    embeddingVersion = "d1d99a1efae6779390caba937d92c54b5bc70e51",
                    metadata = mapOf("sourceRunId" to "source-run", "sourceChunkId" to "source-chunk"),
                    sourceReference = SourceReference(CanonicalServerId(RestTestIds.chunkId.value), "file:///repo/runs/task.md"),
                ),
            ),
            nextCursor = "next-semantic-cursor",
        )

        val response = controller.semanticSearch(
            SemanticSearchRequest(
                q = " decision ",
                projectId = RestTestIds.projectId.value,
                iterationId = RestTestIds.iterationId.value,
                artifactType = "document_chunk",
                sourcePath = "runs/task.md",
                taskId = RestTestIds.taskId.value,
                runId = RestTestIds.runId.value,
                metadataFilters = mapOf("phase" to "gate-d"),
                limit = 5,
                cursor = "semantic-cursor",
            ),
        )

        assertThat(semanticSearch.received).isEqualTo(
            SemanticSearchQuery(
                query = "decision",
                projectId = RestTestIds.projectId,
                iterationId = RestTestIds.iterationId,
                artifactType = ArtifactType.DOCUMENT_CHUNK,
                sourcePath = "runs/task.md",
                taskId = RestTestIds.taskId,
                runId = RestTestIds.runId,
                metadataFilters = mapOf("phase" to "gate-d"),
                limit = 5,
                cursor = "semantic-cursor",
            ),
        )
        assertThat(response.items.single().embeddingModel).isEqualTo("intfloat/multilingual-e5-small")
        assertThat(response.items.single().sourceIds.sourceRunId).isEqualTo("source-run")
        assertThat(response.items.single().citation.lineage.chunkId).isEqualTo(RestTestIds.chunkId.value)
        assertThat(response.items.single().citation.sourceReference?.uri).isEqualTo("file:///repo/runs/task.md")
        assertThat(response.nextCursor).isEqualTo("next-semantic-cursor")

        assertThatThrownBy {
            controller.semanticSearch(SemanticSearchRequest(q = " "))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("q is required")
        assertThatThrownBy {
            controller.semanticSearch(SemanticSearchRequest(q = "decision", artifactType = "not_an_artifact"))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("artifactType has invalid value")
    }

    @Test
    fun `q-only search and chunk contracts reject removed client embedding fields`() {
        val errors = RestExceptionHandler()
        val notConfigured = errors.embeddingProviderNotConfigured(ProviderNotConfiguredException())
        val unavailable = errors.embeddingProviderUnavailable(ProviderUnavailableException())

        assertThat(notConfigured.statusCode.value()).isEqualTo(503)
        assertThat(notConfigured.body?.error).isEqualTo("embedding_provider_not_configured")
        assertThat(unavailable.statusCode.value()).isEqualTo(503)
        assertThat(unavailable.body?.error).isEqualTo("embedding_provider_unavailable")
        assertThat(JacksonObjectMapperConfig().objectMapper().writeValueAsString(notConfigured.body))
            .contains("embedding_provider_not_configured")
        val objectMapper = JacksonObjectMapperConfig().objectMapper()
        val removedSearchFields = listOf(
            "embedding" to "[0.1]",
            "embeddingModel" to "\"legacy-model\"",
            "embeddingDimension" to "384",
            "embeddingVersion" to "\"legacy-version\"",
            "distanceMetric" to "\"COSINE\"",
        )
        removedSearchFields.forEach { (field, value) ->
            assertThatThrownBy {
                objectMapper.readValue("""{"q":"결제 취소 정책","$field":$value}""", SemanticSearchRequest::class.java)
            }.isInstanceOf(UnrecognizedPropertyException::class.java)
            assertThatThrownBy {
                objectMapper.readValue("""{"q":"결제 취소 정책","$field":$value}""", HybridSearchRequest::class.java)
            }.isInstanceOf(UnrecognizedPropertyException::class.java)
        }
        listOf(
            "embeddingSet" to "{}",
            "embedding" to "[0.1]",
            "embeddingHash" to "\"legacy-hash\"",
        ).forEach { (field, value) ->
            assertThatThrownBy {
                objectMapper.readValue(
                    """{"documentId":"document","chunks":[{"chunk":{},"$field":$value}]}""",
                    DocumentChunksBulkWriteRequest::class.java,
                )
            }.isInstanceOf(UnrecognizedPropertyException::class.java)
        }
    }

    @Test
    fun `removed vector endpoint has no replacement handler`() {
        val mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(RestExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(JacksonObjectMapperConfig().objectMapper()))
            .build()

        mockMvc.perform(
            post("/api/search/vector")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"embedding":[0.1]}"""),
        ).andExpect(status().isNotFound())
    }

    @Test
    fun `semantic request-bound cursor failures return validation error responses`() {
        val boundSemanticSearch = SemanticSearchUseCaseService(
            FakeEmbeddingPort(
                activeEmbeddingTarget = ActiveEmbeddingTarget(
                    profile = V2EmbeddingProfile.fixed,
                    embeddingSetId = EmbeddingSetId("active-v2-set"),
                ),
            ),
            object : VectorSearchPort {
                override fun search(query: VectorSearchQuery): PagedResult<VectorSearchMatch> = PagedResult(emptyList())

                override fun search(query: ActiveVectorSearchQuery): PagedResult<VectorSearchMatch> = PagedResult(emptyList())
            },
        )
        val strictController = QueryRestController(
            findArtifactsUseCase = findArtifacts,
            keywordSearchUseCase = keywordSearch,
            semanticSearchUseCase = boundSemanticSearch,
            hybridSearchUseCase = hybridSearch,
            findArtifactGraphNodesUseCase = findGraphNodes,
            traceArtifactGraphUseCase = traceGraph,
        )
        val mockMvc = MockMvcBuilders.standaloneSetup(strictController)
            .setControllerAdvice(RestExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(JacksonObjectMapperConfig().objectMapper()))
            .build()

        mockMvc.perform(
            post("/api/search/semantic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"q":"decision","cursor":"legacy-unbound-cursor"}"""),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation_error"))
    }

    @Test
    fun `hybrid controller propagates configured and unavailable provider failures as 503 responses`() {
        val mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(RestExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(JacksonObjectMapperConfig().objectMapper()))
            .build()

        hybridSearch.failure = ProviderNotConfiguredException()
        mockMvc.perform(
            post("/api/search/hybrid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"q":"decision"}"""),
        )
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("embedding_provider_not_configured"))

        hybridSearch.failure = ProviderUnavailableException()
        mockMvc.perform(
            post("/api/search/hybrid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"q":"decision"}"""),
        )
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("embedding_provider_unavailable"))
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
    fun `hybrid request normalizes q and resolves fusion defaults before use case execution`() {
        val query = HybridSearchRequest(q = "  decision  ", limit = 25).toQuery()

        assertThat(query.query).isEqualTo("decision")
        assertThat(query.rrfK).isEqualTo(DEFAULT_RRF_K)
        assertThat(query.candidateLimit).isEqualTo(100)
        assertThat(query.limit).isEqualTo(25)
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

private class FakeSemanticSearchUseCase : SemanticSearchUseCase {
    var received: SemanticSearchQuery? = null
    var result: PagedResult<VectorSearchMatch> = PagedResult(emptyList(), nextCursor = "next-semantic-cursor")

    override fun semanticSearch(query: SemanticSearchQuery): PagedResult<VectorSearchMatch> {
        received = query
        return result
    }
}

private class FakeHybridSearchUseCase : HybridSearchUseCase {
    var received: HybridSearchQuery? = null
    var result: PagedResult<HybridSearchMatch> = PagedResult(emptyList(), nextCursor = "next-hybrid-cursor")
    var failure: RuntimeException? = null

    override fun hybridSearch(query: HybridSearchQuery): PagedResult<HybridSearchMatch> {
        received = query
        failure?.let { throw it }
        return result
    }
}

private class FakeFindArtifactGraphNodesUseCase : FindArtifactGraphNodesUseCase {
    override fun findGraphNodes(query: GraphNodeSearchQuery): List<ArtifactNode> = emptyList()
}

private class FakeTraceArtifactGraphUseCase : TraceArtifactGraphUseCase {
    override fun traceGraph(query: GraphTraceQuery): ArtifactTrace = error("not used")
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
