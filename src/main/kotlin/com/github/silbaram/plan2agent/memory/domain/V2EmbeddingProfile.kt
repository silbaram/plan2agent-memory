package com.github.silbaram.plan2agent.memory.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Immutable vector-space contract for the initial server-managed embedding target.
 *
 * Runtime provider bindings deliberately live outside this type. Changing any value
 * here represents a new embedding profile and must not overwrite an existing set.
 */
data class V2EmbeddingProfile(
    val schemaVersion: String = SCHEMA_VERSION,
    val model: String = MODEL,
    val revision: String = REVISION,
    val modelSha256: String = MODEL_SHA256,
    val tokenizerSha256: String = TOKENIZER_SHA256,
    val modelOutputName: String = MODEL_OUTPUT_NAME,
    val dimension: Int = DIMENSION,
    val distanceMetric: DistanceMetric = DistanceMetric.COSINE,
    val documentPrefix: String = DOCUMENT_PREFIX,
    val queryPrefix: String = QUERY_PREFIX,
    val tokenizer: V2TokenizerContract = V2TokenizerContract(),
    val pooling: V2Pooling = V2Pooling.ATTENTION_MASKED_MEAN_V1,
    val l2Normalize: Boolean = true,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Embedding profile schemaVersion must be $SCHEMA_VERSION" }
        require(model == MODEL) { "Embedding profile model must be $MODEL" }
        require(revision == REVISION) { "Embedding profile revision must be pinned" }
        require(modelSha256 == MODEL_SHA256 && modelSha256.isSha256()) { "Embedding profile modelSha256 must be pinned" }
        require(tokenizerSha256 == TOKENIZER_SHA256 && tokenizerSha256.isSha256()) {
            "Embedding profile tokenizerSha256 must be pinned"
        }
        require(modelOutputName == MODEL_OUTPUT_NAME) { "Embedding profile modelOutputName must be $MODEL_OUTPUT_NAME" }
        require(dimension == DIMENSION) { "Embedding profile dimension must be $DIMENSION" }
        require(distanceMetric == DistanceMetric.COSINE) { "Embedding profile distanceMetric must be COSINE" }
        require(documentPrefix == DOCUMENT_PREFIX) { "Embedding profile documentPrefix must be exact E5 passage prefix" }
        require(queryPrefix == QUERY_PREFIX) { "Embedding profile queryPrefix must be exact E5 query prefix" }
        require(tokenizer == V2TokenizerContract()) { "Embedding profile tokenizer contract must be fixed" }
        require(pooling == V2Pooling.ATTENTION_MASKED_MEAN_V1) { "Embedding profile pooling must be attention masked mean" }
        require(l2Normalize) { "Embedding profile must use L2 normalization" }
    }

    fun documentInput(content: String): String = documentPrefix + content

    fun queryInput(query: String): String = queryPrefix + query

    /**
     * The complete vector-space manifest used to identify an immutable embedding set.
     *
     * Provider kind, local artifact URIs, and worker tuning are runtime bindings, not
     * vector-space properties, and are intentionally absent from this manifest.
     */
    val manifest: V2EmbeddingProfileManifest
        get() = V2EmbeddingProfileManifest(
            profileSchema = schemaVersion,
            model = model,
            revision = revision,
            modelSha256 = modelSha256,
            tokenizerSha256 = tokenizerSha256,
            modelOutputName = modelOutputName,
            dimension = dimension,
            distanceMetric = distanceMetric.canonicalValue,
            documentPrefix = documentPrefix,
            queryPrefix = queryPrefix,
            tokenizer = tokenizer.toManifest(),
            pooling = pooling.canonicalValue,
            l2Normalize = l2Normalize,
        )

    /** A deterministic `sha256:<lowercase-hex>` identifier for [manifest]. */
    val fingerprint: String
        get() = manifest.fingerprint

    companion object {
        const val SCHEMA_VERSION = "p2a.embedding-profile.v1"
        const val MODEL = "intfloat/multilingual-e5-small"
        const val REVISION = "d1d99a1efae6779390caba937d92c54b5bc70e51"
        const val MODEL_SHA256 = "ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665"
        const val TOKENIZER_SHA256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"
        const val MODEL_OUTPUT_NAME = "last_hidden_state"
        const val DIMENSION = 384
        const val DOCUMENT_PREFIX = "passage: "
        const val QUERY_PREFIX = "query: "

        val fixed: V2EmbeddingProfile = V2EmbeddingProfile()
    }
}

data class V2TokenizerContract(
    val addSpecialTokens: Boolean = true,
    val modelMaxLength: Int = 512,
    val maxLength: Int = 512,
    val padding: Boolean = true,
    val truncation: Boolean = true,
) {
    init {
        require(addSpecialTokens) { "Embedding tokenizer must add special tokens" }
        require(modelMaxLength == 512) { "Embedding tokenizer modelMaxLength must be 512" }
        require(maxLength == 512) { "Embedding tokenizer maxLength must be 512" }
        require(padding) { "Embedding tokenizer must pad" }
        require(truncation) { "Embedding tokenizer must truncate" }
    }
}

/**
 * A serializable vector-space manifest. Its canonical JSON and fingerprint are stable
 * across deployment-specific configuration changes.
 */
data class V2EmbeddingProfileManifest(
    val profileSchema: String,
    val model: String,
    val revision: String,
    val modelSha256: String,
    val tokenizerSha256: String,
    val modelOutputName: String,
    val dimension: Int,
    val distanceMetric: String,
    val documentPrefix: String,
    val queryPrefix: String,
    val tokenizer: V2TokenizerManifest,
    val pooling: String,
    val l2Normalize: Boolean,
) {
    /**
     * Whitespace-free UTF-8 JSON with a fixed field order. Do not use a general-purpose
     * serializer here: serializer configuration could make a persisted fingerprint vary.
     */
    val canonicalJson: String
        get() = buildString {
            append('{')
            appendJsonField("profileSchema", profileSchema, leadingComma = false)
            appendJsonField("model", model)
            appendJsonField("revision", revision)
            appendJsonField("modelSha256", modelSha256)
            appendJsonField("tokenizerSha256", tokenizerSha256)
            appendJsonField("modelOutputName", modelOutputName)
            appendJsonField("dimension", dimension)
            appendJsonField("distanceMetric", distanceMetric)
            appendJsonField("documentPrefix", documentPrefix)
            appendJsonField("queryPrefix", queryPrefix)
            append(",\"tokenizer\":{")
            appendJsonField("addSpecialTokens", tokenizer.addSpecialTokens, leadingComma = false)
            appendJsonField("modelMaxLength", tokenizer.modelMaxLength)
            appendJsonField("maxLength", tokenizer.maxLength)
            appendJsonField("padding", tokenizer.padding)
            appendJsonField("truncation", tokenizer.truncation)
            append('}')
            appendJsonField("pooling", pooling)
            appendJsonField("l2Normalize", l2Normalize)
            append('}')
        }

    /** SHA-256 over [canonicalJson] encoded as UTF-8. */
    val fingerprint: String
        get() = "sha256:" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(StandardCharsets.UTF_8)),
        )
}

data class V2TokenizerManifest(
    val addSpecialTokens: Boolean,
    val modelMaxLength: Int,
    val maxLength: Int,
    val padding: Boolean,
    val truncation: Boolean,
)

enum class V2Pooling {
    ATTENTION_MASKED_MEAN_V1,
}

private val DistanceMetric.canonicalValue: String
    get() = name.lowercase()

private val V2Pooling.canonicalValue: String
    get() = name.lowercase()

private fun V2TokenizerContract.toManifest(): V2TokenizerManifest =
    V2TokenizerManifest(
        addSpecialTokens = addSpecialTokens,
        modelMaxLength = modelMaxLength,
        maxLength = maxLength,
        padding = padding,
        truncation = truncation,
    )

private fun StringBuilder.appendJsonField(name: String, value: String, leadingComma: Boolean = true) {
    if (leadingComma) {
        append(',')
    }
    appendJsonString(name)
    append(':')
    appendJsonString(value)
}

private fun StringBuilder.appendJsonField(name: String, value: Int, leadingComma: Boolean = true) {
    if (leadingComma) {
        append(',')
    }
    appendJsonString(name)
    append(':')
    append(value)
}

private fun StringBuilder.appendJsonField(name: String, value: Boolean, leadingComma: Boolean = true) {
    if (leadingComma) {
        append(',')
    }
    appendJsonString(name)
    append(':')
    append(value)
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
