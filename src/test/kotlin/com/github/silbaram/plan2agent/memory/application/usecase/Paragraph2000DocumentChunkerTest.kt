package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class Paragraph2000DocumentChunkerTest {
    private val chunker = Paragraph2000DocumentChunker()

    @Test
    fun `matches P2A paragraph aggregation whitespace and CRLF golden vectors`() {
        val vectors = listOf(
            GoldenVector(
                content = " alpha \n\n beta ",
                chunks = listOf(
                    GoldenChunk(
                        content = "alpha\n\nbeta",
                        hash = "5cc50ee6ceb81cc7b80693ba8e2a6a8de78be7449e0d90dcf19093b2b6ef1ab2",
                        id = "e850dc36-a729-500a-976c-649794f32294",
                        tokenEstimate = 3,
                    ),
                ),
            ),
            GoldenVector(
                content = "  first \r\n\r\n second  ",
                chunks = listOf(
                    GoldenChunk(
                        content = "first \r\n\r\n second",
                        hash = "cf2a8dd82e294fd0279cbabaf3c96f97827ea0d1e62342564f99028752b4d401",
                        id = "bdd53ea0-16e0-5c02-ac0b-7a56d4d934a4",
                        tokenEstimate = 5,
                    ),
                ),
            ),
            GoldenVector(
                content = "${"a".repeat(1998)}\n\nb",
                chunks = listOf(
                    GoldenChunk(
                        content = "a".repeat(1998),
                        hash = "79f866f19047e2de5b869f1767135d69cbe633c2b8b589d67860948e1d6fc4ba",
                        id = "887881e3-7700-59a1-ba37-52c37e842fd9",
                        tokenEstimate = 500,
                    ),
                    GoldenChunk(
                        content = "b",
                        hash = "2c3b670cb6cdc903d127a5a263a4393b96d6644e60720d759cc4181c8d5a0f1c",
                        id = "c0030cb0-ece9-54ff-985a-cd0672df27b9",
                        tokenEstimate = 1,
                    ),
                ),
            ),
        )

        vectors.forEach(::assertGoldenVector)
    }

    @Test
    fun `matches P2A 2000 and 2001 UTF-16 boundary golden vectors`() {
        val vectors = listOf(
            GoldenVector(
                content = "a".repeat(2000),
                chunks = listOf(
                    GoldenChunk(
                        content = "a".repeat(2000),
                        hash = "87e434816d5fa7995e115ed40e77afeb41070fb2e08e03fb539ff745201aef78",
                        id = "7c4aad49-c799-5368-a684-e6254ac04d11",
                        tokenEstimate = 500,
                    ),
                ),
            ),
            GoldenVector(
                content = "b".repeat(2001),
                chunks = listOf(
                    GoldenChunk(
                        content = "b".repeat(2000),
                        hash = "4e149f01be40a42e61dbc7dbb69514a92d1a1fdf1e4919ac61929d2b45283cd1",
                        id = "c72269b3-9612-5cb6-84ed-c414c1b8872b",
                        tokenEstimate = 500,
                    ),
                    GoldenChunk(
                        content = "b",
                        hash = "2c3b670cb6cdc903d127a5a263a4393b96d6644e60720d759cc4181c8d5a0f1c",
                        id = "c0030cb0-ece9-54ff-985a-cd0672df27b9",
                        tokenEstimate = 1,
                    ),
                ),
            ),
        )

        vectors.forEach(::assertGoldenVector)
    }

    @Test
    fun `uses UTF-16 lengths for Korean and emoji exactly like P2A`() {
        assertGoldenVector(
            GoldenVector(
                content = "한글\n\n${"😀".repeat(1000)}a",
                chunks = listOf(
                    GoldenChunk(
                        content = "한글",
                        hash = "9169f5b43a51f75ed1d10a8a0fb787041442f51c55707c0684f9c74f62a2717a",
                        id = "d7399c8a-8d89-521c-b3e3-60b8e87d7aca",
                        tokenEstimate = 1,
                    ),
                    GoldenChunk(
                        content = "😀".repeat(1000),
                        hash = "03c224b81f65482dbf94cef48eef0a241145ae60f301384cbb53d38c3e8098eb",
                        id = "30de609e-0d18-55fe-9be2-ed7d7de7c9fc",
                        tokenEstimate = 500,
                    ),
                    GoldenChunk(
                        content = "a",
                        hash = "ea5bcf73a283628f13a823865d7174f55b409497c91955952dc470bad94d6524",
                        id = "b3cfb995-51e6-5b4c-90e4-6880b44a7403",
                        tokenEstimate = 1,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `server identity and metadata override client values while source location is preserved`() {
        val chunks = chunker.chunk(document("first\n\nsecond"))

        assertThat(chunks).hasSize(1)
        val chunk = chunks.single()
        assertThat(chunk.documentId.value).isEqualTo(DOCUMENT_ID)
        assertThat(chunk.sourceReference).isEqualTo(
            SourceReference(
                canonicalServerId = CanonicalServerId(chunk.id.value),
                uri = "file:///repo/spec.md",
                path = "spec.md",
                startLine = 2,
                endLine = 8,
                fragment = "chunk-0",
            ),
        )
        assertThat(chunk.metadata).containsEntry("sourceChunkId", "source-doc:chunk-0")
        assertThat(chunk.metadata).containsEntry("parentDocumentId", DOCUMENT_ID)
        assertThat(chunk.metadata).containsEntry("chunkStrategy", "paragraph-2000")
        assertThat(chunk.metadata).containsEntry("documentRole", "spec")
    }

    private fun assertGoldenVector(vector: GoldenVector) {
        val chunks = chunker.chunk(document(vector.content))
        assertThat(chunks).hasSize(vector.chunks.size)
        chunks.zip(vector.chunks).forEachIndexed { index, (actual, expected) ->
            assertThat(actual.chunkIndex).isEqualTo(index)
            assertThat(actual.content).isEqualTo(expected.content)
            assertThat(actual.chunkHash.value).isEqualTo(expected.hash)
            assertThat(actual.id.value).isEqualTo(expected.id)
            assertThat(actual.tokenEstimate).isEqualTo(expected.tokenEstimate)
        }
    }

    private fun document(content: String): DocumentSnapshot =
        DocumentSnapshot(
            id = DocumentId(DOCUMENT_ID),
            projectId = ProjectId("22222222-2222-2222-2222-222222222222"),
            iterationId = IterationId("33333333-3333-3333-3333-333333333333"),
            sourceDocumentId = SourceDocumentId("source-doc"),
            sourcePath = "spec.md",
            snapshotVersion = 1,
            artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
            title = "Spec",
            content = content,
            contentHash = ContentHash("document-hash"),
            sourceReference = SourceReference(
                canonicalServerId = CanonicalServerId(DOCUMENT_ID),
                uri = "file:///repo/spec.md",
                path = "spec.md",
                startLine = 2,
                endLine = 8,
                fragment = "document-fragment",
            ),
            capturedAt = NOW,
            createdAt = NOW,
            metadata = mapOf(
                "documentRole" to "spec",
                "sourceChunkId" to "client-value",
                "parentDocumentId" to "client-value",
                "chunkStrategy" to "client-value",
            ),
        )

    private data class GoldenVector(
        val content: String,
        val chunks: List<GoldenChunk>,
    )

    private data class GoldenChunk(
        val content: String,
        val hash: String,
        val id: String,
        val tokenEstimate: Int,
    )

    private companion object {
        const val DOCUMENT_ID = "11111111-1111-1111-1111-111111111111"
        val NOW: Instant = Instant.parse("2026-08-17T00:00:00Z")
    }
}
