package com.github.silbaram.plan2agent.memory.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmbeddingJobFailureTest {
    @Test
    fun `sanitizes and bounds untrusted error messages by Unicode character count`() {
        val failure = EmbeddingJobFailure.fromUntrustedMessage(
            EmbeddingJobErrorCode.PROVIDER_CONTRACT_INVALID,
            "\n  transient\t" + "😀".repeat(600),
        )

        assertThat(failure.code).isEqualTo(EmbeddingJobErrorCode.PROVIDER_CONTRACT_INVALID)
        assertThat(failure.message).doesNotContain("\n", "\t")
        assertThat(failure.message.codePointCount(0, failure.message.length)).isLessThanOrEqualTo(512)
    }
}
