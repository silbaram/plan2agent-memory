package com.github.silbaram.plan2agent.memory.domain

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
}
