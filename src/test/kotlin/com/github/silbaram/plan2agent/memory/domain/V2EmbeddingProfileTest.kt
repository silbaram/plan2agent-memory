package com.github.silbaram.plan2agent.memory.domain

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class V2EmbeddingProfileTest {
    @Test
    fun `fixed profile defines the pinned multilingual E5 vector-space contract`() {
        val profile = V2EmbeddingProfile.fixed

        assertThat(profile.schemaVersion).isEqualTo("p2a.embedding-profile.v1")
        assertThat(profile.model).isEqualTo("intfloat/multilingual-e5-small")
        assertThat(profile.revision).isEqualTo("d1d99a1efae6779390caba937d92c54b5bc70e51")
        assertThat(profile.modelSha256).isEqualTo("ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665")
        assertThat(profile.tokenizerSha256).isEqualTo("0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39")
        assertThat(profile.dimension).isEqualTo(384)
        assertThat(profile.distanceMetric).isEqualTo(DistanceMetric.COSINE)
        assertThat(profile.tokenizer.modelMaxLength).isEqualTo(512)
        assertThat(profile.tokenizer.maxLength).isEqualTo(512)
        assertThat(profile.pooling).isEqualTo(V2Pooling.ATTENTION_MASKED_MEAN_V1)
        assertThat(profile.l2Normalize).isTrue()
    }

    @Test
    fun `fixed profile has a valid canonical manifest and deterministic golden fingerprint`() {
        val profile = V2EmbeddingProfile.fixed

        assertThat(profile.manifest.canonicalJson).isEqualTo(EXPECTED_CANONICAL_JSON)
        assertThat(ObjectMapper().readTree(profile.manifest.canonicalJson).toString())
            .isEqualTo(EXPECTED_CANONICAL_JSON)
        assertThat(profile.fingerprint).isEqualTo(EXPECTED_FINGERPRINT)
        assertThat(profile.fingerprint).matches("sha256:[0-9a-f]{64}")
    }

    @Test
    fun `canonical manifest is independent of construction order and runtime binding settings`() {
        val manifest = V2EmbeddingProfile.fixed.manifest
        val reconstructedInDifferentArgumentOrder = V2EmbeddingProfileManifest(
            l2Normalize = manifest.l2Normalize,
            pooling = manifest.pooling,
            tokenizer = manifest.tokenizer,
            queryPrefix = manifest.queryPrefix,
            documentPrefix = manifest.documentPrefix,
            distanceMetric = manifest.distanceMetric,
            dimension = manifest.dimension,
            modelOutputName = manifest.modelOutputName,
            tokenizerSha256 = manifest.tokenizerSha256,
            modelSha256 = manifest.modelSha256,
            revision = manifest.revision,
            model = manifest.model,
            profileSchema = manifest.profileSchema,
        )
        val runtimeBindings = listOf(
            RuntimeBinding(
                provider = "none",
                modelArtifactUri = "file:///opt/models/first/model.onnx",
                tokenizerArtifactUri = "file:///opt/models/first/tokenizer.json",
                workerConcurrency = 1,
                workerClaimBatchSize = 16,
            ),
            RuntimeBinding(
                provider = "transformers",
                modelArtifactUri = "file:///mnt/rotated/model.onnx?credential=not-a-fingerprint-input",
                tokenizerArtifactUri = "file:///mnt/rotated/tokenizer.json?credential=not-a-fingerprint-input",
                workerConcurrency = 8,
                workerClaimBatchSize = 64,
            ),
        )

        assertThat(reconstructedInDifferentArgumentOrder.canonicalJson).isEqualTo(manifest.canonicalJson)
        assertThat(reconstructedInDifferentArgumentOrder.fingerprint).isEqualTo(manifest.fingerprint)
        assertThat(runtimeBindings.map { V2EmbeddingProfile.fixed.fingerprint })
            .containsOnly(V2EmbeddingProfile.fixed.fingerprint)
    }

    @Test
    fun `every vector space manifest field changes the fingerprint`() {
        val manifest = V2EmbeddingProfile.fixed.manifest
        val variations = listOf(
            manifest.copy(profileSchema = "p2a.embedding-profile.v2"),
            manifest.copy(model = "intfloat/multilingual-e5-base"),
            manifest.copy(revision = "0000000000000000000000000000000000000000"),
            manifest.copy(modelSha256 = "0".repeat(64)),
            manifest.copy(tokenizerSha256 = "1".repeat(64)),
            manifest.copy(modelOutputName = "pooled_output"),
            manifest.copy(dimension = 385),
            manifest.copy(distanceMetric = "inner_product"),
            manifest.copy(documentPrefix = "passage:"),
            manifest.copy(queryPrefix = "query:"),
            manifest.copy(tokenizer = manifest.tokenizer.copy(addSpecialTokens = false)),
            manifest.copy(tokenizer = manifest.tokenizer.copy(modelMaxLength = 256)),
            manifest.copy(tokenizer = manifest.tokenizer.copy(maxLength = 256)),
            manifest.copy(tokenizer = manifest.tokenizer.copy(padding = false)),
            manifest.copy(tokenizer = manifest.tokenizer.copy(truncation = false)),
            manifest.copy(pooling = "cls_token"),
            manifest.copy(l2Normalize = false),
        )

        variations.forEach { variation ->
            assertThat(variation.fingerprint).isNotEqualTo(manifest.fingerprint)
        }
    }

    @Test
    fun `prefix methods preserve the required trailing ASCII space`() {
        val profile = V2EmbeddingProfile.fixed

        assertThat(profile.documentInput("결제 정책")).isEqualTo("passage: 결제 정책")
        assertThat(profile.queryInput("결제 정책")).isEqualTo("query: 결제 정책")
    }

    @Test
    fun `profile rejects mutation of fixed vector-space fields`() {
        assertThatIllegalArgumentException()
            .isThrownBy { V2EmbeddingProfile.fixed.copy(dimension = 385) }
            .withMessage("Embedding profile dimension must be 384")

        assertThatIllegalArgumentException()
            .isThrownBy { V2EmbeddingProfile.fixed.copy(queryPrefix = "query:") }
            .withMessage("Embedding profile queryPrefix must be exact E5 query prefix")
    }

    private data class RuntimeBinding(
        val provider: String,
        val modelArtifactUri: String,
        val tokenizerArtifactUri: String,
        val workerConcurrency: Int,
        val workerClaimBatchSize: Int,
    )

    private companion object {
        const val EXPECTED_CANONICAL_JSON =
            "{\"profileSchema\":\"p2a.embedding-profile.v1\",\"model\":\"intfloat/multilingual-e5-small\",\"revision\":\"d1d99a1efae6779390caba937d92c54b5bc70e51\",\"modelSha256\":\"ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665\",\"tokenizerSha256\":\"0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39\",\"modelOutputName\":\"last_hidden_state\",\"dimension\":384,\"distanceMetric\":\"cosine\",\"documentPrefix\":\"passage: \",\"queryPrefix\":\"query: \",\"tokenizer\":{\"addSpecialTokens\":true,\"modelMaxLength\":512,\"maxLength\":512,\"padding\":true,\"truncation\":true},\"pooling\":\"attention_masked_mean_v1\",\"l2Normalize\":true}"
        const val EXPECTED_FINGERPRINT = "sha256:0bc822ab3bf2558f89838b6dd617e0f656098ceb809f9a6d33359e0e5889e3e5"
    }
}
