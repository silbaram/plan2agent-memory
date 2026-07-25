package com.github.silbaram.plan2agent.memory.config

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolver
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolutionException
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderUnavailableException
import com.github.silbaram.plan2agent.memory.application.observability.EmbeddingObservability
import com.github.silbaram.plan2agent.memory.application.observability.EmbeddingProviderInitializationOutcome
import com.github.silbaram.plan2agent.memory.adapter.out.embedding.LocalEmbeddingRuntime
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import org.springframework.ai.transformers.TransformersEmbeddingModel
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.beans.factory.DisposableBean
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the local Transformers provider startup without exposing a Spring AI model as a bean.
 *
 * Local model artifacts are verified before Spring AI is allowed to open them. Initialization is
 * deliberately deferred until the application is live so a bad model or native runtime cannot
 * prevent baseline liveness from starting.
 */
class TransformersEmbeddingProviderLifecycle(
    private val embeddingProperties: EmbeddingProperties,
    private val artifactVerifier: TransformersArtifactVerifier,
    private val modelFactory: TransformersEmbeddingModelFactory,
    private val activeEmbeddingProfileResolver: ActiveEmbeddingProfileResolver,
    private val initializationExecutor: ExecutorService = newInitializationExecutor(),
    private val observability: EmbeddingObservability = EmbeddingObservability.noop,
) : ApplicationListener<ApplicationReadyEvent>, DisposableBean, LocalEmbeddingRuntime {
    private val state = AtomicReference(EmbeddingProviderState.INITIALIZING)
    private val initializationScheduled = AtomicBoolean(false)
    private val initializedModel = AtomicReference<TransformersEmbeddingModelSession?>(null)
    private val resolvedEmbeddingSetId = AtomicReference<EmbeddingSetId?>(null)

    override val providerState: EmbeddingProviderState
        get() = state.get()

    /** The verified active V2 set, available only while this provider is ready. */
    override val activeEmbeddingSetId: EmbeddingSetId?
        get() = resolvedEmbeddingSetId.get()

    /** Invokes the initialized local model with fully prepared tokenizer input. */
    override fun embed(input: String): FloatArray {
        if (providerState != EmbeddingProviderState.READY) {
            throw ProviderUnavailableException()
        }
        val model = initializedModel.get() ?: throw ProviderUnavailableException()
        return try {
            model.embed(input)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) {
                throw failure
            }
            throw ProviderUnavailableException()
        }
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        scheduleInitialization()
    }

    /** Schedules the one-time initialization that is normally triggered by [ApplicationReadyEvent]. */
    fun scheduleInitialization() {
        if (!initializationScheduled.compareAndSet(false, true)) {
            return
        }

        try {
            initializationExecutor.execute(::initialize)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) {
                throw failure
            }
            transitionToUnavailable(failure, INITIALIZATION_SCHEDULING_FAILED)
        }
    }

    override fun destroy() {
        initializedModel.set(null)
        resolvedEmbeddingSetId.set(null)
        initializationExecutor.shutdownNow()
    }

    private fun initialize() {
        val initializationStartedAt = System.nanoTime()
        logger.atInfo()
            .addKeyValue("event", INITIALIZATION_STARTED)
            .addKeyValue("provider", PROVIDER_NAME)
            .log("Embedding provider initialization started")

        try {
            val activeEmbeddingSetId = activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId()
            val artifacts = artifactVerifier.verify(embeddingProperties, V2EmbeddingProfile.fixed)
            val model = modelFactory.create(artifacts)
            val dimension = model.warmUp(V2EmbeddingProfile.fixed.documentInput(WARM_UP_DOCUMENT))
            check(dimension == V2EmbeddingProfile.fixed.dimension) {
                "Transformers warm-up returned dimension $dimension, expected ${V2EmbeddingProfile.fixed.dimension}"
            }
            initializedModel.set(model)
            resolvedEmbeddingSetId.set(activeEmbeddingSetId)
            logger.atInfo()
                .addKeyValue("event", INITIALIZATION_READY)
                .addKeyValue("provider", PROVIDER_NAME)
                .addKeyValue("dimension", dimension)
                .addKeyValue("durationMs", elapsedMillis(initializationStartedAt))
                .log("Embedding provider initialization ready")
            state.set(EmbeddingProviderState.READY)
            observability.recordProviderInitialization(EmbeddingProviderInitializationOutcome.READY)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) {
                throw failure
            }
            transitionToUnavailable(failure)
        }
    }

    private fun transitionToUnavailable(failure: Throwable, reason: String = unavailableReason(failure)) {
        initializedModel.set(null)
        resolvedEmbeddingSetId.set(null)
        logger.atWarn()
            .addKeyValue("event", INITIALIZATION_UNAVAILABLE)
            .addKeyValue("provider", PROVIDER_NAME)
            .addKeyValue("reason", reason)
            .log("Embedding provider initialization unavailable")
        state.set(EmbeddingProviderState.UNAVAILABLE)
        observability.recordProviderInitialization(EmbeddingProviderInitializationOutcome.UNAVAILABLE)
    }

    private fun unavailableReason(failure: Throwable): String =
        when (failure) {
            is TransformersArtifactValidationException -> ARTIFACT_VALIDATION_FAILED
            is ActiveEmbeddingProfileResolutionException -> ACTIVE_PROFILE_UNAVAILABLE
            else -> MODEL_INITIALIZATION_FAILED
        }

    private fun elapsedMillis(initializationStartedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initializationStartedAt)

    companion object {
        private const val WARM_UP_DOCUMENT = "p2a transformers provider warm-up"
        private const val PROVIDER_NAME = "transformers"
        private const val INITIALIZATION_STARTED = "embedding_provider_initialization_started"
        private const val INITIALIZATION_READY = "embedding_provider_initialization_ready"
        private const val INITIALIZATION_UNAVAILABLE = "embedding_provider_initialization_unavailable"
        private const val INITIALIZATION_SCHEDULING_FAILED = "initialization_scheduling_failed"
        private const val ARTIFACT_VALIDATION_FAILED = "artifact_validation_failed"
        private const val ACTIVE_PROFILE_UNAVAILABLE = "active_embedding_profile_unavailable"
        private const val MODEL_INITIALIZATION_FAILED = "model_initialization_failed"
        private val logger = LoggerFactory.getLogger(TransformersEmbeddingProviderLifecycle::class.java)

        private fun newInitializationExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor(
                ThreadFactory { runnable ->
                    Thread(runnable, "p2a-transformers-provider-initializer").apply { isDaemon = true }
                },
            )
    }
}

data class TransformersEmbeddingArtifacts(
    val modelArtifactUri: URI,
    val tokenizerArtifactUri: URI,
    val modelOutputName: String,
)

fun interface TransformersArtifactVerifier {
    fun verify(
        embeddingProperties: EmbeddingProperties,
        profile: V2EmbeddingProfile,
    ): TransformersEmbeddingArtifacts
}

class FileSystemTransformersArtifactVerifier : TransformersArtifactVerifier {
    override fun verify(
        embeddingProperties: EmbeddingProperties,
        profile: V2EmbeddingProfile,
    ): TransformersEmbeddingArtifacts {
        val modelArtifactUri = requireFileUri(embeddingProperties.modelArtifactUri)
        val tokenizerArtifactUri = requireFileUri(embeddingProperties.tokenizerArtifactUri)

        verifyChecksum(modelArtifactUri, profile.modelSha256)
        verifyChecksum(tokenizerArtifactUri, profile.tokenizerSha256)
        if (profile.modelOutputName.isBlank()) {
            throw TransformersArtifactValidationException()
        }

        return TransformersEmbeddingArtifacts(
            modelArtifactUri = modelArtifactUri,
            tokenizerArtifactUri = tokenizerArtifactUri,
            modelOutputName = profile.modelOutputName,
        )
    }

    private fun requireFileUri(uri: URI?): URI {
        if (uri == null || !uri.scheme.equals("file", ignoreCase = true)) {
            throw TransformersArtifactValidationException()
        }

        val readableFile = try {
            val path = Path.of(uri)
            Files.isRegularFile(path) && Files.isReadable(path)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) {
                throw failure
            }
            false
        }
        if (!readableFile) {
            throw TransformersArtifactValidationException()
        }
        return uri
    }

    private fun verifyChecksum(uri: URI, expectedChecksum: String) {
        val actualChecksum = try {
            Files.newInputStream(Path.of(uri)).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
                HexFormat.of().formatHex(digest.digest())
            }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) {
                throw failure
            }
            throw TransformersArtifactValidationException()
        }
        if (actualChecksum != expectedChecksum) {
            throw TransformersArtifactValidationException()
        }
    }
}

/** A safe failure for local model validation; artifact locations and contents stay private. */
class TransformersArtifactValidationException : IllegalStateException("Transformers artifact validation failed")

fun interface TransformersEmbeddingModelFactory {
    fun create(artifacts: TransformersEmbeddingArtifacts): TransformersEmbeddingModelSession
}

fun interface TransformersEmbeddingModelSession {
    fun warmUp(document: String): Int

    /** Returns Spring AI's attention-mask mean-pooled output for one tokenized input. */
    fun embed(input: String): FloatArray =
        throw ProviderUnavailableException()
}

class SpringAiTransformersEmbeddingModelFactory(
    private val modelLoader: TransformersEmbeddingModelLoader = SpringAiTransformersEmbeddingModelLoader(),
) : TransformersEmbeddingModelFactory {
    override fun create(artifacts: TransformersEmbeddingArtifacts): TransformersEmbeddingModelSession {
        return modelLoader.load(
            artifacts,
            TransformersTokenizerOptions.fixed,
        )
    }
}

fun interface TransformersEmbeddingModelLoader {
    fun load(
        artifacts: TransformersEmbeddingArtifacts,
        tokenizerOptions: Map<String, String>,
    ): TransformersEmbeddingModelSession
}

class SpringAiTransformersEmbeddingModelLoader : TransformersEmbeddingModelLoader {
    override fun load(
        artifacts: TransformersEmbeddingArtifacts,
        tokenizerOptions: Map<String, String>,
    ): TransformersEmbeddingModelSession {
        val model = TransformersEmbeddingModel().apply {
            setModelResource(artifacts.modelArtifactUri.toString())
            setTokenizerResource(artifacts.tokenizerArtifactUri.toString())
            setModelOutputName(artifacts.modelOutputName)
            setTokenizerOptions(tokenizerOptions)
            setDisableCaching(true)
            afterPropertiesSet()
        }
        return object : TransformersEmbeddingModelSession {
            override fun warmUp(document: String): Int = embed(document).size

            override fun embed(input: String): FloatArray = model.embed(input)
        }
    }
}

object TransformersTokenizerOptions {
    val fixed: Map<String, String> = mapOf(
        "addSpecialTokens" to V2EmbeddingProfile.fixed.tokenizer.addSpecialTokens.toString(),
        "modelMaxLength" to V2EmbeddingProfile.fixed.tokenizer.modelMaxLength.toString(),
        "maxLength" to V2EmbeddingProfile.fixed.tokenizer.maxLength.toString(),
        "padding" to V2EmbeddingProfile.fixed.tokenizer.padding.toString(),
        "truncation" to V2EmbeddingProfile.fixed.tokenizer.truncation.toString(),
    )
}
