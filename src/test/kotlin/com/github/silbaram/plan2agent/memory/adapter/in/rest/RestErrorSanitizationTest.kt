package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.port.out.ProviderUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RestErrorSanitizationTest {
    @Test
    fun `provider failures retain a stable 503 code without reflecting provider details`() {
        val rawProviderFailure = "body={credential=do-not-expose} file:///private/model.onnx chunk=결제 취소 정책"

        val response = RestExceptionHandler().embeddingProviderUnavailable(
            ProviderUnavailableException(rawProviderFailure),
        )

        assertThat(response.statusCode.value()).isEqualTo(503)
        assertThat(response.body?.error).isEqualTo("embedding_provider_unavailable")
        assertThat(response.body?.message).isEqualTo("Embedding provider is unavailable")
        assertThat(response.body?.message).doesNotContain("credential", "file:", "결제")
    }

    @Test
    fun `validation errors suppress accidental path credential and stack trace text`() {
        val rawFailure = "credential=do-not-expose at file:///private/model.onnx\n at provider.Model.embed(Model.kt:12)"

        val response = RestExceptionHandler().validation(IllegalArgumentException(rawFailure))

        assertThat(response.body?.error).isEqualTo("validation_error")
        assertThat(response.body?.message).isEqualTo("Bad Request")
        assertThat(response.body?.message).doesNotContain("credential", "file:", "Model.kt")
    }
}
