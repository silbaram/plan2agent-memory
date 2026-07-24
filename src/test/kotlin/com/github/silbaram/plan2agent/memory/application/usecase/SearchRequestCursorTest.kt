package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

class SearchRequestCursorTest {
    private val activeEmbeddingSetId = EmbeddingSetId("active-v2-set")
    private val semanticQuery = SemanticSearchQuery(
        query = "decision",
        projectId = ProjectId("project-1"),
        iterationId = IterationId("iteration-1"),
        artifactType = ArtifactType.DOCUMENT_CHUNK,
        sourcePath = "runs/task.md",
        taskId = TaskId("task-1"),
        runId = RunId("run-1"),
        metadataFilters = linkedMapOf("phase" to "gate-d", "owner" to "memory"),
        limit = 2,
    )

    @Test
    fun `canonical semantic fingerprint binds endpoint query active target and every filter`() {
        val fingerprint = SearchRequestCursor.semanticFingerprint(semanticQuery, activeEmbeddingSetId)
        val canonicalJson =
            "{\"endpointType\":\"semantic\",\"q\":\"decision\",\"activeEmbeddingSetId\":\"active-v2-set\"," +
                "\"projectId\":\"project-1\",\"iterationId\":\"iteration-1\",\"artifactType\":\"DOCUMENT_CHUNK\"," +
                "\"sourcePath\":\"runs/task.md\",\"taskId\":\"task-1\",\"runId\":\"run-1\"," +
                "\"metadataFilters\":{\"owner\":\"memory\",\"phase\":\"gate-d\"}}"
        val cursor = SearchRequestCursor.encode(fingerprint, "vector-continuation")

        assertThat(fingerprint).isEqualTo(sha256(canonicalJson))
        assertThat(SearchRequestCursor.decode(cursor, fingerprint)).isEqualTo("vector-continuation")

        listOf(
            SearchRequestCursor.hybridFingerprint(
                HybridSearchQuery(
                    query = semanticQuery.query,
                    projectId = semanticQuery.projectId,
                    iterationId = semanticQuery.iterationId,
                    artifactType = semanticQuery.artifactType,
                    sourcePath = semanticQuery.sourcePath,
                    taskId = semanticQuery.taskId,
                    runId = semanticQuery.runId,
                    metadataFilters = semanticQuery.metadataFilters,
                ),
                activeEmbeddingSetId,
            ),
            SearchRequestCursor.semanticFingerprint(semanticQuery.copy(query = "other"), activeEmbeddingSetId),
            SearchRequestCursor.semanticFingerprint(semanticQuery.copy(projectId = ProjectId("project-2")), activeEmbeddingSetId),
            SearchRequestCursor.semanticFingerprint(semanticQuery.copy(iterationId = IterationId("iteration-2")), activeEmbeddingSetId),
            SearchRequestCursor.semanticFingerprint(semanticQuery.copy(artifactType = ArtifactType.DOCUMENT_SNAPSHOT), activeEmbeddingSetId),
            SearchRequestCursor.semanticFingerprint(semanticQuery.copy(sourcePath = "runs/other.md"), activeEmbeddingSetId),
            SearchRequestCursor.semanticFingerprint(semanticQuery.copy(taskId = TaskId("task-2")), activeEmbeddingSetId),
            SearchRequestCursor.semanticFingerprint(semanticQuery.copy(runId = RunId("run-2")), activeEmbeddingSetId),
            SearchRequestCursor.semanticFingerprint(
                semanticQuery.copy(metadataFilters = mapOf("phase" to "gate-c", "owner" to "memory")),
                activeEmbeddingSetId,
            ),
            SearchRequestCursor.semanticFingerprint(semanticQuery, EmbeddingSetId("new-active-v2-set")),
        ).forEach { changedFingerprint ->
            assertThatThrownBy { SearchRequestCursor.decode(cursor, changedFingerprint) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("cursor does not match this request")
        }
    }

    @Test
    fun `metadata order and page limit do not change a bound request but fusion settings do`() {
        val reorderedMetadata = semanticQuery.copy(
            metadataFilters = linkedMapOf("owner" to "memory", "phase" to "gate-d"),
            limit = 10,
        )
        val semanticFingerprint = SearchRequestCursor.semanticFingerprint(semanticQuery, activeEmbeddingSetId)

        assertThat(SearchRequestCursor.semanticFingerprint(reorderedMetadata, activeEmbeddingSetId))
            .isEqualTo(semanticFingerprint)

        val hybridQuery = HybridSearchQuery(
            query = semanticQuery.query,
            projectId = semanticQuery.projectId,
            iterationId = semanticQuery.iterationId,
            artifactType = semanticQuery.artifactType,
            sourcePath = semanticQuery.sourcePath,
            taskId = semanticQuery.taskId,
            runId = semanticQuery.runId,
            metadataFilters = semanticQuery.metadataFilters,
            rrfK = 77,
            candidateLimit = 30,
            limit = 2,
        )
        val hybridFingerprint = SearchRequestCursor.hybridFingerprint(hybridQuery, activeEmbeddingSetId)
        val cursor = SearchRequestCursor.encode(hybridFingerprint, "rrf-continuation")

        assertThat(
            SearchRequestCursor.hybridFingerprint(
                hybridQuery.copy(
                    metadataFilters = linkedMapOf("owner" to "memory", "phase" to "gate-d"),
                    limit = 5,
                ),
                activeEmbeddingSetId,
            ),
        ).isEqualTo(hybridFingerprint)
        assertThat(
            SearchRequestCursor.decode(
                cursor,
                SearchRequestCursor.hybridFingerprint(hybridQuery.copy(limit = 5), activeEmbeddingSetId),
            ),
        ).isEqualTo("rrf-continuation")

        listOf(hybridQuery.copy(rrfK = 78), hybridQuery.copy(candidateLimit = 31)).forEach { changedQuery ->
            assertThatThrownBy {
                SearchRequestCursor.decode(
                    cursor,
                    SearchRequestCursor.hybridFingerprint(changedQuery, activeEmbeddingSetId),
                )
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("cursor does not match this request")
        }
    }

    @Test
    fun `rejects malformed and unsupported cursor schemas`() {
        val fingerprint = SearchRequestCursor.semanticFingerprint(semanticQuery, activeEmbeddingSetId)
        val legacyVectorCursor = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"kind\":\"vector.v1\",\"sortValue\":0.1}".toByteArray(StandardCharsets.UTF_8))
        val legacyHybridCursor = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("hybrid.v1|0.1|1|chunk:1".toByteArray(StandardCharsets.UTF_8))
        val unsupportedVersion = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"cursorSchemaVersion\":2,\"continuationKey\":\"key\",\"requestFingerprint\":\"$fingerprint\"}"
                    .toByteArray(StandardCharsets.UTF_8),
            )

        listOf("legacy-unbound-cursor", legacyVectorCursor, legacyHybridCursor).forEach { legacyCursor ->
            assertThatThrownBy { SearchRequestCursor.decode(legacyCursor, fingerprint) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("cursor has invalid format")
        }
        assertThatThrownBy { SearchRequestCursor.decode(unsupportedVersion, fingerprint) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("cursor has unsupported schema version")
    }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
        )
}
