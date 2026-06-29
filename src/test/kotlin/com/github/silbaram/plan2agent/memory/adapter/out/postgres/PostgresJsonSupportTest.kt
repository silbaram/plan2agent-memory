package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PostgresJsonSupportTest {
    private val json = PostgresJsonSupport(jacksonObjectMapper())

    @Test
    fun `round-trips source reference through metadata`() {
        val sourceReference = SourceReference(
            canonicalServerId = CanonicalServerId("canonical-1"),
            uri = "file:///repo/task-graph.json",
            path = "task-graph.json",
            startLine = 10,
            endLine = 20,
            fragment = "task-4",
        )

        val metadata = json.withSourceReference(mapOf("owner" to "p2a"), sourceReference)

        assertThat(json.sourceReferenceFrom(metadata)).isEqualTo(sourceReference)
        assertThat(json.withoutReservedMetadata(metadata)).containsExactlyEntriesOf(mapOf("owner" to "p2a"))
    }

    @Test
    fun `round-trips task graph dependency edges`() {
        val edges = listOf(
            DependencyEdgeJson(fromTaskId = "task-a", toTaskId = "task-b"),
            DependencyEdgeJson(fromTaskId = "task-b", toTaskId = "task-c"),
        )

        val encoded = json.dependencyEdgesToJson(edges)

        assertThat(json.dependencyEdgesFromJson(encoded)).containsExactlyElementsOf(edges)
    }
}
