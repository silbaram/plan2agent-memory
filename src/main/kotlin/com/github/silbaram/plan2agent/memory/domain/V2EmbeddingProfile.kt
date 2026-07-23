package com.github.silbaram.plan2agent.memory.domain

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

enum class V2Pooling {
    ATTENTION_MASKED_MEAN_V1,
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
