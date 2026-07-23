package com.github.silbaram.plan2agent.memory.config

import com.github.silbaram.plan2agent.memory.adapter.out.embedding.NoConfiguredEmbeddingProvider
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI

@ConfigurationProperties(prefix = "p2a.embedding", ignoreUnknownFields = false)
data class EmbeddingProperties(
    val provider: EmbeddingProviderKind = EmbeddingProviderKind.NONE,
    val modelArtifactUri: URI? = null,
    val tokenizerArtifactUri: URI? = null,
)

enum class EmbeddingProviderKind {
    NONE,
    TRANSFORMERS,
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbeddingProperties::class)
class EmbeddingProviderConfiguration {
    @Bean
    @ConditionalOnProperty(
        prefix = "p2a.embedding",
        name = ["provider"],
        havingValue = "none",
        matchIfMissing = true,
    )
    fun noConfiguredEmbeddingProvider(): EmbeddingPort = NoConfiguredEmbeddingProvider()

    @Bean
    @ConditionalOnProperty(
        prefix = "p2a.embedding",
        name = ["provider"],
        havingValue = "transformers",
    )
    @ConditionalOnMissingBean
    fun transformersArtifactVerifier(): TransformersArtifactVerifier = FileSystemTransformersArtifactVerifier()

    @Bean
    @ConditionalOnProperty(
        prefix = "p2a.embedding",
        name = ["provider"],
        havingValue = "transformers",
    )
    @ConditionalOnMissingBean
    fun transformersEmbeddingModelFactory(): TransformersEmbeddingModelFactory =
        SpringAiTransformersEmbeddingModelFactory()

    @Bean
    @ConditionalOnProperty(
        prefix = "p2a.embedding",
        name = ["provider"],
        havingValue = "transformers",
    )
    @ConditionalOnMissingBean
    fun transformersEmbeddingProviderLifecycle(
        embeddingProperties: EmbeddingProperties,
        artifactVerifier: TransformersArtifactVerifier,
        modelFactory: TransformersEmbeddingModelFactory,
    ): TransformersEmbeddingProviderLifecycle =
        TransformersEmbeddingProviderLifecycle(embeddingProperties, artifactVerifier, modelFactory)
}
