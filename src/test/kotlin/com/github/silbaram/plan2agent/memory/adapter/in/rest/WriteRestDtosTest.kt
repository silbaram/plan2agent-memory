package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.silbaram.plan2agent.memory.application.usecase.DocumentChunkingStrategy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class WriteRestDtosTest {
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun `absent chunking remains snapshot-only while exact opt-in is accepted`() {
        val snapshotOnly = objectMapper.readValue<DocumentSnapshotWriteRequest>("{}")
        val optIn = objectMapper.readValue<DocumentSnapshotWriteRequest>(
            """{"chunking":{"strategy":"paragraph-2000"}}""",
        )

        assertThat(snapshotOnly.toChunkingStrategy()).isNull()
        assertThat(optIn.toChunkingStrategy()).isEqualTo(DocumentChunkingStrategy.PARAGRAPH_2000)
    }

    @Test
    fun `malformed and unknown chunking values are rejected`() {
        val invalidJson = listOf(
            """{"chunking":{}}""",
            """{"chunking":{"strategy":"paragraph-1000"}}""",
            """{"chunking":{"strategy":"paragraph-2000","overlap":0}}""",
            """{"chunking":{"strategy":2000}}""",
            """{"chunking":"paragraph-2000"}""",
        )

        invalidJson.forEach { json ->
            assertThatThrownBy {
                objectMapper.readValue<DocumentSnapshotWriteRequest>(json).toChunkingStrategy()
            }.isInstanceOf(Exception::class.java)
        }
        assertThatThrownBy {
            objectMapper.readValue<DocumentSnapshotWriteRequest>("""{"chunking":null}""")
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `chunking acknowledgment is omitted for legacy responses and included for opt-in`() {
        val base = DocumentSnapshotResponse(
            documentId = "11111111-1111-1111-1111-111111111111",
            projectId = "22222222-2222-2222-2222-222222222222",
            sourceDocumentId = "source-doc",
            sourcePath = "spec.md",
            snapshotVersion = 1,
            artifactType = "DOCUMENT_SNAPSHOT",
            title = "Spec",
            contentHash = "document-hash",
            lineage = ArtifactLineageResponse(
                projectId = "22222222-2222-2222-2222-222222222222",
                sourcePath = "spec.md",
                contentHash = "document-hash",
                snapshotVersion = 1,
            ),
            capturedAt = Instant.parse("2026-08-17T00:00:00Z"),
            createdAt = Instant.parse("2026-08-17T00:00:00Z"),
            metadata = emptyMap(),
        )

        val snapshotOnlyJson = objectMapper.readTree(objectMapper.writeValueAsString(base))
        val optInJson = objectMapper.readTree(
            objectMapper.writeValueAsString(
                base.copy(
                    chunking = DocumentSnapshotChunkingResponse(
                        strategy = "paragraph-2000",
                        chunkCount = 2,
                    ),
                ),
            ),
        )

        assertThat(snapshotOnlyJson.has("chunking")).isFalse()
        assertThat(optInJson["chunking"]["strategy"].asText()).isEqualTo("paragraph-2000")
        assertThat(optInJson["chunking"]["chunkCount"].asInt()).isEqualTo(2)
    }
}
