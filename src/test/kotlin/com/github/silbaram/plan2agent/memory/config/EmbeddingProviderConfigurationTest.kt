package com.github.silbaram.plan2agent.memory.config

import com.github.silbaram.plan2agent.memory.application.observability.EmbeddingInferenceOperation
import com.github.silbaram.plan2agent.memory.application.observability.EmbeddingJobOutcome
import com.github.silbaram.plan2agent.memory.application.observability.EmbeddingObservability
import com.github.silbaram.plan2agent.memory.application.observability.EmbeddingProviderInitializationOutcome
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolutionException
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolver
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.util.function.Supplier
import java.util.concurrent.CopyOnWriteArrayList

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
            .withBean(ActiveEmbeddingProfileResolver::class.java, Supplier { resolvedProfile() })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(EmbeddingPort::class.java)
                assertThat(context).doesNotHaveBean(EmbeddingModel::class.java)
                val properties = context.getBean(EmbeddingProperties::class.java)
                assertThat(properties.provider).isEqualTo(EmbeddingProviderKind.TRANSFORMERS)
                assertThat(properties.modelArtifactUri).hasToString("file:/models/model.onnx")
                assertThat(properties.tokenizerArtifactUri).hasToString("file:/models/tokenizer.json")
                assertThat(context.getBean(TransformersEmbeddingProviderLifecycle::class.java).providerState)
                    .isEqualTo(EmbeddingProviderState.INITIALIZING)
                assertThat(context.getBean(EmbeddingPort::class.java).providerState)
                    .isEqualTo(EmbeddingProviderState.INITIALIZING)
            }
    }

    @Test
    fun `transformers lifecycle makes missing artifact URIs unavailable after ready`() {
        contextRunner
            .withPropertyValues("p2a.embedding.provider=transformers")
            .withBean(ActiveEmbeddingProfileResolver::class.java, Supplier { resolvedProfile() })
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
            .withBean(ActiveEmbeddingProfileResolver::class.java, Supplier { resolvedProfile() })
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
            .withBean(ActiveEmbeddingProfileResolver::class.java, Supplier { resolvedProfile() })
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
                .withBean(ActiveEmbeddingProfileResolver::class.java, Supplier { resolvedProfile() })
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
    fun `artifact validation failures have a stable non-sensitive message`() {
        val verifier = FileSystemTransformersArtifactVerifier()
        val missingModel = URI.create("file:///private/models/credential=do-not-expose/model.onnx")
        val missingTokenizer = URI.create("file:///private/models/credential=do-not-expose/tokenizer.json")
        val model = Files.createTempFile("p2a-model-integrity-", ".onnx")
        val tokenizer = Files.createTempFile("p2a-tokenizer-integrity-", ".json")

        try {
            assertThatThrownBy {
                verifier.verify(
                    EmbeddingProperties(
                        provider = EmbeddingProviderKind.TRANSFORMERS,
                        modelArtifactUri = missingModel,
                        tokenizerArtifactUri = missingTokenizer,
                    ),
                    V2EmbeddingProfile.fixed,
                )
            }
                .isInstanceOf(TransformersArtifactValidationException::class.java)
                .hasMessage("Transformers artifact validation failed")
                .hasMessageNotContaining(missingModel.toString())
                .hasMessageNotContaining("credential=do-not-expose")

            assertThatThrownBy {
                verifier.verify(
                    EmbeddingProperties(
                        provider = EmbeddingProviderKind.TRANSFORMERS,
                        modelArtifactUri = model.toUri(),
                        tokenizerArtifactUri = tokenizer.toUri(),
                    ),
                    V2EmbeddingProfile.fixed,
                )
            }
                .isInstanceOf(TransformersArtifactValidationException::class.java)
                .hasMessage("Transformers artifact validation failed")
                .hasMessageNotContaining(model.toString())
                .hasMessageNotContaining(tokenizer.toString())
        } finally {
            Files.deleteIfExists(model)
            Files.deleteIfExists(tokenizer)
        }
    }

    @Test
    fun `transformers lifecycle makes output node failures unavailable without surfacing model details`() {
        transformerContext(
            modelFactory = TransformersEmbeddingModelFactory {
                TransformersEmbeddingModelSession {
                    throw IllegalStateException(
                        "Configured output node exposed credential=do-not-expose at file:///private/model.onnx for source content",
                    )
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
                assertThat(lifecycle.activeEmbeddingSetId).isEqualTo(RESOLVED_EMBEDDING_SET_ID)
            }
    }

    @Test
    fun `transformers lifecycle makes an unresolved active profile unavailable before opening model artifacts`() {
        var factoryCalls = 0
        transformerContext(
            activeProfileResolver = ActiveEmbeddingProfileResolver {
                throw ActiveEmbeddingProfileResolutionException()
            },
            modelFactory = TransformersEmbeddingModelFactory {
                factoryCalls += 1
                TransformersEmbeddingModelSession { V2EmbeddingProfile.fixed.dimension }
            },
        )
            .run { context ->
                val lifecycle = context.getBean(TransformersEmbeddingProviderLifecycle::class.java)

                lifecycle.scheduleInitialization()

                assertThat(awaitProviderState(lifecycle, EmbeddingProviderState.UNAVAILABLE)).isTrue()
                assertThat(factoryCalls).isZero()
                assertThat(lifecycle.activeEmbeddingSetId).isNull()
            }
    }

    @Test
    fun `transformers lifecycle records only stable initialization outcomes across state transitions`() {
        val readyMetrics = RecordingObservability()
        val readyLifecycle = TransformersEmbeddingProviderLifecycle(
            embeddingProperties = EmbeddingProperties(provider = EmbeddingProviderKind.TRANSFORMERS),
            artifactVerifier = verifiedArtifacts(),
            modelFactory = TransformersEmbeddingModelFactory {
                TransformersEmbeddingModelSession { V2EmbeddingProfile.fixed.dimension }
            },
            activeEmbeddingProfileResolver = resolvedProfile(),
            observability = readyMetrics,
        )
        try {
            readyLifecycle.scheduleInitialization()
            assertThat(awaitProviderState(readyLifecycle, EmbeddingProviderState.READY)).isTrue()
            assertThat(readyMetrics.initializationOutcomes)
                .containsExactly(EmbeddingProviderInitializationOutcome.READY)
        } finally {
            readyLifecycle.destroy()
        }

        val unavailableMetrics = RecordingObservability()
        val logger = LoggerFactory.getLogger(TransformersEmbeddingProviderLifecycle::class.java) as Logger
        val logAppender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logAppender)
        val unavailableLifecycle = TransformersEmbeddingProviderLifecycle(
            embeddingProperties = EmbeddingProperties(provider = EmbeddingProviderKind.TRANSFORMERS),
            artifactVerifier = verifiedArtifacts(),
            modelFactory = TransformersEmbeddingModelFactory {
                throw IllegalStateException("body={credential=must-not-leak} file:///private/model.onnx")
            },
            activeEmbeddingProfileResolver = resolvedProfile(),
            observability = unavailableMetrics,
        )
        try {
            unavailableLifecycle.scheduleInitialization()
            assertThat(awaitProviderState(unavailableLifecycle, EmbeddingProviderState.UNAVAILABLE)).isTrue()
            assertThat(unavailableMetrics.initializationOutcomes)
                .containsExactly(EmbeddingProviderInitializationOutcome.UNAVAILABLE)
            assertThat(logAppender.list.map(ILoggingEvent::getFormattedMessage))
                .contains("Embedding provider initialization failed")
                .noneMatch { message ->
                    message.contains("credential=must-not-leak") || message.contains("file:///private/model.onnx")
                }
        } finally {
            unavailableLifecycle.destroy()
            logger.detachAppender(logAppender)
            logAppender.stop()
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
        activeProfileResolver: ActiveEmbeddingProfileResolver = resolvedProfile(),
    ): ApplicationContextRunner =
        contextRunner
            .withPropertyValues("p2a.embedding.provider=transformers")
            .withBean(TransformersArtifactVerifier::class.java, Supplier { artifactVerifier })
            .withBean(TransformersEmbeddingModelFactory::class.java, Supplier { modelFactory })
            .withBean(ActiveEmbeddingProfileResolver::class.java, Supplier { activeProfileResolver })

    private fun resolvedProfile(): ActiveEmbeddingProfileResolver =
        ActiveEmbeddingProfileResolver { RESOLVED_EMBEDDING_SET_ID }

    private fun verifiedArtifacts(): TransformersArtifactVerifier =
        TransformersArtifactVerifier { _, profile ->
            TransformersEmbeddingArtifacts(
                modelArtifactUri = URI.create("file:/verified/model.onnx"),
                tokenizerArtifactUri = URI.create("file:/verified/tokenizer.json"),
                modelOutputName = profile.modelOutputName,
            )
        }

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

    private companion object {
        val RESOLVED_EMBEDDING_SET_ID = EmbeddingSetId("10d2d6cc-a2d8-4c4d-a7ab-3443a29533b5")
    }

    private class RecordingObservability : EmbeddingObservability {
        val initializationOutcomes = CopyOnWriteArrayList<EmbeddingProviderInitializationOutcome>()

        override fun recordProviderInitialization(outcome: EmbeddingProviderInitializationOutcome) {
            initializationOutcomes += outcome
        }

        override fun recordJobOutcome(outcome: EmbeddingJobOutcome) = Unit

        override fun <T> recordInference(operation: EmbeddingInferenceOperation, block: () -> T): T = block()

        override fun refreshJobStatusCounts() = Unit
    }
}
