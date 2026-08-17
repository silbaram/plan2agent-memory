package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Pure Kotlin port of Plan2Agent's paragraph-2000 document chunker.
 *
 * String boundaries intentionally use UTF-16 code units, matching JavaScript. The implementation
 * does not normalize newlines or parse Markdown because either would change the P2A contract.
 */
class Paragraph2000DocumentChunker {
    fun chunk(document: DocumentSnapshot): List<DocumentChunk> =
        chunkText(document.content).mapIndexed { index, content ->
            val chunkHash = ContentHash(sha256("$index\n$content"))
            val chunkId = DocumentChunkId(stableChunkId(document.id.value, index, chunkHash.value))
            DocumentChunk(
                id = chunkId,
                projectId = document.projectId,
                iterationId = document.iterationId,
                documentId = document.id,
                artifactType = document.artifactType,
                sourcePath = document.sourcePath,
                chunkIndex = index,
                content = content,
                chunkHash = chunkHash,
                tokenEstimate = (content.length + TOKEN_ESTIMATE_DIVISOR - 1) / TOKEN_ESTIMATE_DIVISOR,
                sourceReference = document.sourceReference?.copy(
                    canonicalServerId = CanonicalServerId(chunkId.value),
                    fragment = "chunk-$index",
                ),
                createdAt = document.createdAt,
                metadata = document.metadata + mapOf(
                    "sourceChunkId" to "${document.sourceDocumentId.value}:chunk-$index",
                    "parentDocumentId" to document.id.value,
                    "chunkStrategy" to STRATEGY,
                ),
            )
        }

    internal fun chunkText(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var current = ""
        text.split(PARAGRAPH_SEPARATOR).forEach { paragraph ->
            val normalized = paragraph.trimEcmaScriptWhitespace()
            if (normalized.isEmpty()) return@forEach
            if (normalized.length > MAX_CHUNK_CHARS) {
                if (current.isNotEmpty()) {
                    chunks += current
                    current = ""
                }
                var offset = 0
                while (offset < normalized.length) {
                    chunks += normalized.substring(offset, minOf(offset + MAX_CHUNK_CHARS, normalized.length))
                    offset += MAX_CHUNK_CHARS
                }
                return@forEach
            }

            val next = if (current.isNotEmpty()) "$current\n\n$normalized" else normalized
            if (next.length > MAX_CHUNK_CHARS && current.isNotEmpty()) {
                chunks += current
                current = normalized
            } else {
                current = next
            }
        }
        if (current.isNotEmpty()) chunks += current
        return chunks.ifEmpty { listOf(text.trimEcmaScriptWhitespace()) }
    }

    private fun stableChunkId(documentId: String, index: Int, chunkHash: String): String {
        val stableInput = "[\"p2a-chunk\",\"$documentId\",$index,\"$chunkHash\"]"
        val hex = sha256(stableInput).toCharArray()
        hex[12] = '5'
        hex[16] = ((hex[16].digitToInt(16) and 0x3) or 0x8).toString(16).single()
        val value = hex.concatToString(0, 32)
        return listOf(
            value.substring(0, 8),
            value.substring(8, 12),
            value.substring(12, 16),
            value.substring(16, 20),
            value.substring(20, 32),
        ).joinToString("-")
    }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toNodeUtf8Bytes()),
        )

    /** Node replaces isolated UTF-16 surrogates with U+FFFD before UTF-8 encoding. */
    private fun String.toNodeUtf8Bytes(): ByteArray {
        val normalized = buildString(length) {
            var index = 0
            while (index < this@toNodeUtf8Bytes.length) {
                val current = this@toNodeUtf8Bytes[index]
                when {
                    current.isHighSurrogate() &&
                        index + 1 < this@toNodeUtf8Bytes.length &&
                        this@toNodeUtf8Bytes[index + 1].isLowSurrogate() -> {
                        append(current)
                        append(this@toNodeUtf8Bytes[index + 1])
                        index += 2
                    }
                    current.isSurrogate() -> {
                        append('\uFFFD')
                        index += 1
                    }
                    else -> {
                        append(current)
                        index += 1
                    }
                }
            }
        }
        return normalized.toByteArray(StandardCharsets.UTF_8)
    }

    private fun String.trimEcmaScriptWhitespace(): String = trim { character ->
        character in '\u0009'..'\u000D' ||
            character == '\u0020' ||
            character == '\u00A0' ||
            character == '\u1680' ||
            character in '\u2000'..'\u200A' ||
            character == '\u2028' ||
            character == '\u2029' ||
            character == '\u202F' ||
            character == '\u205F' ||
            character == '\u3000' ||
            character == '\uFEFF'
    }

    companion object {
        const val STRATEGY = "paragraph-2000"
        const val MAX_CHUNK_CHARS = 2000
        private const val TOKEN_ESTIMATE_DIVISOR = 4
        private val PARAGRAPH_SEPARATOR = Regex("\n{2,}")
    }
}
