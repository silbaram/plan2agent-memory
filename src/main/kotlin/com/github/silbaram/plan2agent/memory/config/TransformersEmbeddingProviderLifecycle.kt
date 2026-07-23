package com.github.silbaram.plan2agent.memory.config

import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import org.springframework.ai.transformers.TransformersEmbeddingModel
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.beans.factory.DisposableBean
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
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
    private val initializationExecutor: ExecutorService = newInitializationExecutor(),
) : ApplicationListener<ApplicationReadyEvent>, DisposableBean {
    private val state = AtomicReference(EmbeddingProviderState.INITIALIZING)
    private val initializationScheduled = AtomicBoolean(false)
    private val initializedModel = AtomicReference<TransformersEmbeddingModelSession?>(null)

    val providerState: EmbeddingProviderState
        get() = state.get()

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
            transitionToUnavailable(failure)
        }
    }

    override fun destroy() {
        initializedModel.set(null)
        initializationExecutor.shutdownNow()
    }

    private fun initialize() {
        try {
            val artifacts = artifactVerifier.verify(embeddingProperties, V2EmbeddingProfile.fixed)
            val model = modelFactory.create(artifacts)
            val dimension = model.warmUp(V2EmbeddingProfile.fixed.documentInput(WARM_UP_DOCUMENT))
            check(dimension == V2EmbeddingProfile.fixed.dimension) {
                "Transformers warm-up returned dimension $dimension, expected ${V2EmbeddingProfile.fixed.dimension}"
            }
            initializedModel.set(model)
            state.set(EmbeddingProviderState.READY)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) {
                throw failure
            }
            transitionToUnavailable(failure)
        }
    }

    private fun transitionToUnavailable(@Suppress("UNUSED_PARAMETER") failure: Throwable) {
        state.set(EmbeddingProviderState.UNAVAILABLE)
    }

    companion object {
        private const val WARM_UP_DOCUMENT = "p2a transformers provider warm-up"

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
        val modelArtifactUri = requireFileUri(embeddingProperties.modelArtifactUri, "model artifact")
        val tokenizerArtifactUri = requireFileUri(embeddingProperties.tokenizerArtifactUri, "tokenizer artifact")

        verifyChecksum(modelArtifactUri, profile.modelSha256, "model artifact")
        verifyChecksum(tokenizerArtifactUri, profile.tokenizerSha256, "tokenizer artifact")
        require(profile.modelOutputName.isNotBlank()) { "Transformers model output name must not be blank" }

        return TransformersEmbeddingArtifacts(
            modelArtifactUri = modelArtifactUri,
            tokenizerArtifactUri = tokenizerArtifactUri,
            modelOutputName = profile.modelOutputName,
        )
    }

    private fun requireFileUri(uri: URI?, artifactName: String): URI {
        requireNotNull(uri) { "Transformers $artifactName URI is required" }
        require(uri.scheme == "file") { "Transformers $artifactName URI must use the file scheme" }

        val path = Path.of(uri)
        require(Files.isRegularFile(path) && Files.isReadable(path)) {
            "Transformers $artifactName must be a readable file"
        }
        return uri
    }

    private fun verifyChecksum(uri: URI, expectedChecksum: String, artifactName: String) {
        val actualChecksum = Files.newInputStream(Path.of(uri)).use { input ->
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
        require(actualChecksum == expectedChecksum) { "Transformers $artifactName checksum does not match the embedding profile" }
    }
}

fun interface TransformersEmbeddingModelFactory {
    fun create(artifacts: TransformersEmbeddingArtifacts): TransformersEmbeddingModelSession
}

fun interface TransformersEmbeddingModelSession {
    fun warmUp(document: String): Int
}

class SpringAiTransformersEmbeddingModelFactory : TransformersEmbeddingModelFactory {
    override fun create(artifacts: TransformersEmbeddingArtifacts): TransformersEmbeddingModelSession {
        val model = TransformersEmbeddingModel().apply {
            setModelResource(artifacts.modelArtifactUri.toString())
            setTokenizerResource(artifacts.tokenizerArtifactUri.toString())
            setModelOutputName(artifacts.modelOutputName)
            setDisableCaching(true)
            afterPropertiesSet()
        }
        return TransformersEmbeddingModelSession { document -> model.embed(document).size }
    }
}
