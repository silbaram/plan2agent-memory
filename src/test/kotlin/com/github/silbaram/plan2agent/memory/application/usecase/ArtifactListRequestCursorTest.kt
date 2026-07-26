package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ArtifactListRequestCursorTest {
    @Test
    fun `artifact list fingerprint binds filters and ignores page limit and type order`() {
        val query = FindArtifactsQuery(
            projectId = ProjectId("project-1"),
            iterationId = IterationId("iteration-1"),
            artifactTypes = linkedSetOf(ArtifactType.TASK, ArtifactType.RUN_RECORD),
            sourcePath = "runs/task.json",
            limit = 1,
        )
        val fingerprint = ArtifactListRequestCursor.fingerprint(query)
        val cursor = SearchRequestCursor.encode(fingerprint, "adapter-cursor")

        assertThat(
            ArtifactListRequestCursor.fingerprint(
                query.copy(
                    artifactTypes = linkedSetOf(ArtifactType.RUN_RECORD, ArtifactType.TASK),
                    limit = 50,
                ),
            ),
        ).isEqualTo(fingerprint)
        assertThat(SearchRequestCursor.decode(cursor, ArtifactListRequestCursor.fingerprint(query.copy(limit = 50))))
            .isEqualTo("adapter-cursor")

        listOf(
            query.copy(projectId = ProjectId("project-2")),
            query.copy(iterationId = IterationId("iteration-2")),
            query.copy(artifactTypes = setOf(ArtifactType.TASK)),
            query.copy(sourcePath = "runs/other.json"),
        ).forEach { changedQuery ->
            assertThatThrownBy {
                SearchRequestCursor.decode(cursor, ArtifactListRequestCursor.fingerprint(changedQuery))
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("cursor does not match this request")
        }
    }
}
