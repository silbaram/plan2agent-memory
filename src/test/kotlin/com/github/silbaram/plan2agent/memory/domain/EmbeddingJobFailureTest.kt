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

    @Test
    fun `drops raw provider body credentials paths and chunk content before persistence`() {
        val rawProviderFailure = """
            HTTP 500 body={\"token\":\"credential-should-not-leak\"}
            at file:///private/models/provider.onnx
            chunk=결제 취소 정책 원문
            at provider.Stack.method(Provider.kt:12)
        """.trimIndent()

        val untrusted = EmbeddingJobFailure.fromUntrustedMessage(
            EmbeddingJobErrorCode.PROVIDER_UNAVAILABLE,
            rawProviderFailure,
        )
        val stored = EmbeddingJobFailure.fromStoredMessage(
            EmbeddingJobErrorCode.PROVIDER_UNAVAILABLE,
            rawProviderFailure,
        )

        listOf(untrusted, stored).forEach { failure ->
            assertThat(failure.message).isEqualTo("Embedding provider is unavailable")
            assertThat(failure.message).doesNotContain("credential", "file:", "결제", "Stack")
        }
    }
}
