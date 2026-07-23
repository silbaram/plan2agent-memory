package com.github.silbaram.plan2agent.memory.config

import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.net.URI
import java.nio.file.Files
import java.util.function.Supplier

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
                assertThat(context).doesNotHaveBean(EmbeddingModel::class.java)
                assertThat(context).doesNotHaveBean(TransformersEmbeddingModelFactory::class.java)
                assertThat(context).doesNotHaveBean(TransformersEmbeddingProviderLifecycle::class.java)

                val provider = context.getBean(EmbeddingPort::class.java)
                assertThat(provider.providerState).isEqualTo(EmbeddingProviderState.NOT_CONFIGURED)
                assertThat(provider.activeEmbeddingTarget.profile.dimension).isEqualTo(384)
                assertThatThrownBy { provider.embedQuery("결제 취소") }
                    .isInstanceOf(ProviderNotConfiguredException::class.java)
            }
    }

    @Test
    fun `transformers provider creates only an application lifecycle before application ready`() {
        contextRunner
            .withPropertyValues(
                "p2a.embedding.provider=transformers",
                "p2a.embedding.model-artifact-uri=file:/models/model.onnx",
                "p2a.embedding.tokenizer-artifact-uri=file:/models/tokenizer.json",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(EmbeddingPort::class.java)
                assertThat(context).doesNotHaveBean(EmbeddingModel::class.java)
                val properties = context.getBean(EmbeddingProperties::class.java)
                assertThat(properties.provider).isEqualTo(EmbeddingProviderKind.TRANSFORMERS)
                assertThat(properties.modelArtifactUri).hasToString("file:/models/model.onnx")
                assertThat(properties.tokenizerArtifactUri).hasToString("file:/models/tokenizer.json")
                assertThat(context.getBean(TransformersEmbeddingProviderLifecycle::class.java).providerState)
                    .isEqualTo(EmbeddingProviderState.INITIALIZING)
            }
    }

    @Test
    fun `transformers lifecycle makes missing artifact URIs unavailable after ready`() {
        contextRunner
            .withPropertyValues("p2a.embedding.provider=transformers")
            .run { context ->
                val lifecycle = context.getBean(TransformersEmbeddingProviderLifecycle::class.java)

                lifecycle.scheduleInitialization()

                assertThat(awaitProviderState(lifecycle, EmbeddingProviderState.UNAVAILABLE)).isTrue()
            }
    }

    @Test
    fun `transformers lifecycle makes missing local files unavailable without creating a model`() {
        contextRunner
            .withPropertyValues(
                "p2a.embedding.provider=transformers",
                "p2a.embedding.model-artifact-uri=file:/missing/model.onnx",
                "p2a.embedding.tokenizer-artifact-uri=file:/missing/tokenizer.json",
            )
            .run { context ->
                val lifecycle = context.getBean(TransformersEmbeddingProviderLifecycle::class.java)

                lifecycle.scheduleInitialization()

                assertThat(awaitProviderState(lifecycle, EmbeddingProviderState.UNAVAILABLE)).isTrue()
            }
    }

    @Test
    fun `transformers lifecycle rejects remote artifacts without downloading them`() {
        contextRunner
            .withPropertyValues(
                "p2a.embedding.provider=transformers",
                "p2a.embedding.model-artifact-uri=https://example.invalid/model.onnx",
                "p2a.embedding.tokenizer-artifact-uri=https://example.invalid/tokenizer.json",
            )
            .run { context ->
                val lifecycle = context.getBean(TransformersEmbeddingProviderLifecycle::class.java)

                lifecycle.scheduleInitialization()

                assertThat(awaitProviderState(lifecycle, EmbeddingProviderState.UNAVAILABLE)).isTrue()
            }
    }

    @Test
    fun `transformers lifecycle makes checksum mismatches unavailable offline`() {
        val model = Files.createTempFile("p2a-model-", ".onnx")
        val tokenizer = Files.createTempFile("p2a-tokenizer-", ".json")
        try {
            contextRunner
                .withPropertyValues(
                    "p2a.embedding.provider=transformers",
                    "p2a.embedding.model-artifact-uri=${model.toUri()}",
                    "p2a.embedding.tokenizer-artifact-uri=${tokenizer.toUri()}",
                )
                .run { context ->
                    val lifecycle = context.getBean(TransformersEmbeddingProviderLifecycle::class.java)

                    lifecycle.scheduleInitialization()

                    assertThat(awaitProviderState(lifecycle, EmbeddingProviderState.UNAVAILABLE)).isTrue()
                }
        } finally {
            Files.deleteIfExists(model)
            Files.deleteIfExists(tokenizer)
        }
    }

    @Test
    fun `transformers lifecycle makes output node failures unavailable`() {
        transformerContext(
            modelFactory = TransformersEmbeddingModelFactory {
                TransformersEmbeddingModelSession {
                    throw IllegalStateException("Configured model output node does not exist")
                }
            },
        )
            .run { context ->
                val lifecycle = context.getBean(TransformersEmbeddingProviderLifecycle::class.java)

                lifecycle.scheduleInitialization()

                assertThat(awaitProviderState(lifecycle, EmbeddingProviderState.UNAVAILABLE)).isTrue()
            }
    }

    @Test
    fun `transformers lifecycle makes model factory failures unavailable`() {
        transformerContext(
            modelFactory = TransformersEmbeddingModelFactory {
                throw IllegalStateException("Transformers model factory could not initialize the model")
            },
        )
            .run { context ->
                val lifecycle = context.getBean(TransformersEmbeddingProviderLifecycle::class.java)

                lifecycle.scheduleInitialization()

                assertThat(awaitProviderState(lifecycle, EmbeddingProviderState.UNAVAILABLE)).isTrue()
            }
    }

    @Test
    fun `transformers lifecycle makes warm-up failures unavailable`() {
        transformerContext(
            modelFactory = TransformersEmbeddingModelFactory {
                TransformersEmbeddingModelSession {
                    throw IllegalStateException("Transformers warm-up failed")
                }
            },
        )
            .run { context ->
                val lifecycle = context.getBean(TransformersEmbeddingProviderLifecycle::class.java)

                lifecycle.scheduleInitialization()

                assertThat(awaitProviderState(lifecycle, EmbeddingProviderState.UNAVAILABLE)).isTrue()
            }
    }

    @Test
    fun `transformers lifecycle makes native runtime failures unavailable`() {
        transformerContext(
            modelFactory = TransformersEmbeddingModelFactory {
                throw UnsatisfiedLinkError("ONNX native runtime is unavailable")
            },
        )
            .run { context ->
                val lifecycle = context.getBean(TransformersEmbeddingProviderLifecycle::class.java)

                lifecycle.scheduleInitialization()

                assertThat(awaitProviderState(lifecycle, EmbeddingProviderState.UNAVAILABLE)).isTrue()
            }
    }

    @Test
    fun `transformers lifecycle becomes ready once and only after warm-up succeeds`() {
        var factoryCalls = 0
        transformerContext(
            modelFactory = TransformersEmbeddingModelFactory {
                factoryCalls += 1
                TransformersEmbeddingModelSession { V2EmbeddingProfile.fixed.dimension }
            },
        )
            .run { context ->
                val lifecycle = context.getBean(TransformersEmbeddingProviderLifecycle::class.java)
                assertThat(lifecycle.providerState).isEqualTo(EmbeddingProviderState.INITIALIZING)

                lifecycle.scheduleInitialization()
                lifecycle.scheduleInitialization()

                assertThat(awaitProviderState(lifecycle, EmbeddingProviderState.READY)).isTrue()
                assertThat(factoryCalls).isEqualTo(1)
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

    private fun transformerContext(
        artifactVerifier: TransformersArtifactVerifier = TransformersArtifactVerifier { _, profile ->
            TransformersEmbeddingArtifacts(
                modelArtifactUri = URI.create("file:/verified/model.onnx"),
                tokenizerArtifactUri = URI.create("file:/verified/tokenizer.json"),
                modelOutputName = profile.modelOutputName,
            )
        },
        modelFactory: TransformersEmbeddingModelFactory = TransformersEmbeddingModelFactory {
            TransformersEmbeddingModelSession { V2EmbeddingProfile.fixed.dimension }
        },
    ): ApplicationContextRunner =
        contextRunner
            .withPropertyValues("p2a.embedding.provider=transformers")
            .withBean(TransformersArtifactVerifier::class.java, Supplier { artifactVerifier })
            .withBean(TransformersEmbeddingModelFactory::class.java, Supplier { modelFactory })

    private fun awaitProviderState(
        lifecycle: TransformersEmbeddingProviderLifecycle,
        expectedState: EmbeddingProviderState,
    ): Boolean {
        repeat(100) {
            if (lifecycle.providerState == expectedState) {
                return true
            }
            Thread.sleep(10)
        }
        return false
    }
}
