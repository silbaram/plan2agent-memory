package com.github.silbaram.plan2agent.memory.config

import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class EmbeddingProviderConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(EmbeddingProviderConfiguration::class.java)

    @Test
    fun `none provider starts without model artifacts or provider dependencies`() {
        contextRunner
            .withPropertyValues("p2a.embedding.provider=none")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(EmbeddingPort::class.java)

                val provider = context.getBean(EmbeddingPort::class.java)
                assertThat(provider.providerState).isEqualTo(EmbeddingProviderState.NOT_CONFIGURED)
                assertThat(provider.activeEmbeddingTarget.profile.dimension).isEqualTo(384)
                assertThatThrownBy { provider.embedQuery("결제 취소") }
                    .isInstanceOf(ProviderNotConfiguredException::class.java)
            }
    }

    @Test
    fun `runtime binding accepts only declared provider values and artifact URIs`() {
        contextRunner
            .withPropertyValues(
                "p2a.embedding.provider=transformers",
                "p2a.embedding.model-artifact-uri=file:/models/model.onnx",
                "p2a.embedding.tokenizer-artifact-uri=file:/models/tokenizer.json",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(EmbeddingPort::class.java)
                val properties = context.getBean(EmbeddingProperties::class.java)
                assertThat(properties.provider).isEqualTo(EmbeddingProviderKind.TRANSFORMERS)
                assertThat(properties.modelArtifactUri).hasToString("file:/models/model.onnx")
                assertThat(properties.tokenizerArtifactUri).hasToString("file:/models/tokenizer.json")
            }
    }

    @Test
    fun `runtime binding rejects profile overrides`() {
        contextRunner
            .withPropertyValues(
                "p2a.embedding.provider=none",
                "p2a.embedding.model=another-model",
            )
            .run { context ->
                assertThat(context).hasFailed()
            }
    }
}
