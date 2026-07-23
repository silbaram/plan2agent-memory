package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveVectorSearchQuery
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderUnavailableException
import com.github.silbaram.plan2agent.memory.application.port.out.VectorSearchPort
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
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

class SemanticSearchUseCaseServiceTest {
    private val target = ActiveEmbeddingTarget(
        profile = V2EmbeddingProfile.fixed,
        embeddingSetId = EmbeddingSetId("active-v2-set"),
    )
    private val embeddingPort = FakeEmbeddingPort(activeEmbeddingTarget = target)
    private val vectorSearch = CapturingVectorSearchPort()
    private val service = SemanticSearchUseCaseService(embeddingPort, vectorSearch)

    @Test
    fun `embeds q and searches the exact active target with every retrieval filter`() {
        val queryEmbedding = List(V2EmbeddingProfile.fixed.dimension) { index -> if (index == 0) 1f else 0f }
        embeddingPort.configureEmbedding(FakeEmbeddingMode.QUERY, "결제 취소 정책", queryEmbedding)
        val query = SemanticSearchQuery(
            query = "결제 취소 정책",
            projectId = ProjectId("project-1"),
            iterationId = IterationId("iteration-1"),
            artifactType = ArtifactType.DOCUMENT_CHUNK,
            sourcePath = "runs/task.md",
            taskId = TaskId("task-1"),
            runId = RunId("run-1"),
            metadataFilters = mapOf("phase" to "gate-d"),
            limit = 5,
            cursor = "semantic-cursor",
        )

        val result = service.semanticSearch(query)

        assertThat(embeddingPort.requests).containsExactly(FakeEmbeddingRequest(FakeEmbeddingMode.QUERY, "결제 취소 정책"))
        assertThat(vectorSearch.activeQuery).isEqualTo(
            ActiveVectorSearchQuery(
                embeddingSetId = target.embeddingSetId!!,
                embedding = Embedding(queryEmbedding),
                projectId = query.projectId,
                iterationId = query.iterationId,
                artifactType = query.artifactType,
                sourcePath = query.sourcePath,
                taskId = query.taskId,
                runId = query.runId,
                metadataFilters = query.metadataFilters,
                limit = query.limit,
                cursor = query.cursor,
            ),
        )
        assertThat(result).isEqualTo(vectorSearch.result)
    }

    @Test
    fun `returns a normal empty page while the provider is ready and active set has no vectors`() {
        vectorSearch.result = PagedResult(emptyList())

        val result = service.semanticSearch(SemanticSearchQuery(query = "결제 취소 정책"))

        assertThat(result.items).isEmpty()
        assertThat(result.nextCursor).isNull()
        assertThat(vectorSearch.activeQuery?.embeddingSetId).isEqualTo(target.embeddingSetId)
    }

    @Test
    fun `validates query and filters before invoking the provider`() {
        assertThatThrownBy { SemanticSearchQuery(query = " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("query must not be blank")
        assertThatThrownBy { SemanticSearchQuery(query = "decision", sourcePath = " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sourcePath must not be blank")
        assertThatThrownBy { SemanticSearchQuery(query = "decision", metadataFilters = mapOf(" " to "gate-d")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("metadata filter keys must not be blank")
        assertThat(embeddingPort.requests).isEmpty()
        assertThat(vectorSearch.activeQuery).isNull()
    }

    @Test
    fun `distinguishes a missing provider from unavailable lifecycle and runtime failures`() {
        val query = SemanticSearchQuery(query = "결제 취소 정책")
        val noProvider = SemanticSearchUseCaseService(
            FakeEmbeddingPort(activeEmbeddingTarget = target, providerState = EmbeddingProviderState.NOT_CONFIGURED),
            CapturingVectorSearchPort(),
        )

        assertThatThrownBy { noProvider.semanticSearch(query) }
            .isInstanceOf(ProviderNotConfiguredException::class.java)

        listOf(EmbeddingProviderState.INITIALIZING, EmbeddingProviderState.UNAVAILABLE).forEach { state ->
            val unavailable = SemanticSearchUseCaseService(
                FakeEmbeddingPort(activeEmbeddingTarget = target, providerState = state),
                CapturingVectorSearchPort(),
            )

            assertThatThrownBy { unavailable.semanticSearch(query) }
                .isInstanceOf(ProviderUnavailableException::class.java)
        }

        val temporarilyFailing = SemanticSearchUseCaseService(
            FakeEmbeddingPort(activeEmbeddingTarget = target)
                .fail(FakeEmbeddingMode.QUERY, FakeEmbeddingFailure.RETRYABLE_UNAVAILABLE),
            CapturingVectorSearchPort(),
        )
        assertThatThrownBy { temporarilyFailing.semanticSearch(query) }
            .isInstanceOf(ProviderUnavailableException::class.java)

        val runtimeFailing = SemanticSearchUseCaseService(
            FakeEmbeddingPort(activeEmbeddingTarget = target)
                .beforeNextEmbed(FakeEmbeddingMode.QUERY) { throw IllegalStateException("temporary provider failure") },
            CapturingVectorSearchPort(),
        )
        assertThatThrownBy { runtimeFailing.semanticSearch(query) }
            .isInstanceOf(ProviderUnavailableException::class.java)
    }
}

private class CapturingVectorSearchPort : VectorSearchPort {
    var activeQuery: ActiveVectorSearchQuery? = null
    var result: PagedResult<VectorSearchMatch> = PagedResult(emptyList())

    override fun search(query: VectorSearchQuery): PagedResult<VectorSearchMatch> =
        error("Legacy vector query must not be used for semantic search")

    override fun search(query: ActiveVectorSearchQuery): PagedResult<VectorSearchMatch> {
        activeQuery = query
        return result
    }
}
