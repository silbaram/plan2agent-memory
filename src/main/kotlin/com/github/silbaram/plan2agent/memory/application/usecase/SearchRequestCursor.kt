package com.github.silbaram.plan2agent.memory.application.usecase

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

/**
 * Binds public semantic and hybrid cursors to the resolved request that produced them.
 *
 * The continuation key remains opaque to callers. Semantic search stores the adapter's vector
 * cursor there, while hybrid search stores its RRF continuation key.
 */
internal object SearchRequestCursor {
    private const val cursorSchemaVersion = 1
    private val objectMapper = ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun semanticFingerprint(query: SemanticSearchQuery, activeEmbeddingSetId: EmbeddingSetId): String =
        canonicalFingerprint(
            endpointType = "semantic",
            query = query.query,
            activeEmbeddingSetId = activeEmbeddingSetId,
            projectId = query.projectId?.value,
            iterationId = query.iterationId?.value,
            artifactType = query.artifactType?.name,
            sourcePath = query.sourcePath,
            taskId = query.taskId?.value,
            runId = query.runId?.value,
            metadataFilters = query.metadataFilters,
        )

    fun hybridFingerprint(query: HybridSearchQuery, activeEmbeddingSetId: EmbeddingSetId): String =
        canonicalFingerprint(
            endpointType = "hybrid",
            query = query.query,
            activeEmbeddingSetId = activeEmbeddingSetId,
            projectId = query.projectId?.value,
            iterationId = query.iterationId?.value,
            artifactType = query.artifactType?.name,
            sourcePath = query.sourcePath,
            taskId = query.taskId?.value,
            runId = query.runId?.value,
            metadataFilters = query.metadataFilters,
            rrfK = query.rrfK,
            candidateLimit = query.candidateLimit,
        )

    fun encode(requestFingerprint: String, continuationKey: String): String {
        require(continuationKey.isNotBlank()) { "cursor continuation key must not be blank" }
        val payload = buildString {
            append('{')
            appendJsonNumberField("cursorSchemaVersion", cursorSchemaVersion)
            append(',')
            appendJsonField("continuationKey", continuationKey)
            append(',')
            appendJsonField("requestFingerprint", requestFingerprint)
            append('}')
        }
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(value: String, expectedRequestFingerprint: String): String {
        val decoded = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("cursor has invalid format")
        }
        val payload = try {
            objectMapper.readTree(decoded)
        } catch (_: Exception) {
            throw IllegalArgumentException("cursor has invalid format")
        }
        require(payload.isObject) { "cursor has invalid format" }

        val version = payload.get("cursorSchemaVersion")
        require(version?.isInt == true) { "cursor has invalid format" }
        require(version.intValue() == cursorSchemaVersion) { "cursor has unsupported schema version" }

        val continuationKey = payload.get("continuationKey")
        require(continuationKey?.isTextual == true && continuationKey.asText().isNotBlank()) {
            "cursor has invalid format"
        }
        val requestFingerprint = payload.get("requestFingerprint")
        require(requestFingerprint?.isTextual == true && requestFingerprint.asText().isNotBlank()) {
            "cursor has invalid format"
        }
        require(requestFingerprint.asText() == expectedRequestFingerprint) { "cursor does not match this request" }
        return continuationKey.asText()
    }

    private fun canonicalFingerprint(
        endpointType: String,
        query: String,
        activeEmbeddingSetId: EmbeddingSetId,
        projectId: String?,
        iterationId: String?,
        artifactType: String?,
        sourcePath: String?,
        taskId: String?,
        runId: String?,
        metadataFilters: Map<String, String>,
        rrfK: Int? = null,
        candidateLimit: Int? = null,
    ): String {
        val canonicalJson = buildString {
            append('{')
            appendJsonField("endpointType", endpointType)
            append(',')
            appendJsonField("q", query)
            append(',')
            appendJsonField("activeEmbeddingSetId", activeEmbeddingSetId.value)
            append(',')
            appendJsonField("projectId", projectId)
            append(',')
            appendJsonField("iterationId", iterationId)
            append(',')
            appendJsonField("artifactType", artifactType)
            append(',')
            appendJsonField("sourcePath", sourcePath)
            append(',')
            appendJsonField("taskId", taskId)
            append(',')
            appendJsonField("runId", runId)
            append(',')
            append("\"metadataFilters\":")
            appendMetadataFilters(metadataFilters)
            if (rrfK != null && candidateLimit != null) {
                append(',')
                appendJsonNumberField("rrfK", rrfK)
                append(',')
                appendJsonNumberField("candidateLimit", candidateLimit)
            }
            append('}')
        }
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun StringBuilder.appendMetadataFilters(metadataFilters: Map<String, String>) {
        append('{')
        metadataFilters.toSortedMap().entries.forEachIndexed { index, (key, value) ->
            if (index > 0) {
                append(',')
            }
            appendJsonField(key, value)
        }
        append('}')
    }

    private fun StringBuilder.appendJsonField(name: String, value: String?) {
        append('"')
        append(name)
        append("\":")
        if (value == null) {
            append("null")
        } else {
            append('"')
            append(value.jsonEscaped())
            append('"')
        }
    }

    private fun StringBuilder.appendJsonNumberField(name: String, value: Int) {
        append('"')
        append(name)
        append("\":")
        append(value)
    }

    private fun String.jsonEscaped(): String = buildString {
        this@jsonEscaped.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }
}
