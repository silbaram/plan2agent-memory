package com.github.silbaram.plan2agent.memory.adapter.out.embedding

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingResult
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile

/**
 * Offline-safe binding used until a local provider is explicitly configured.
 * It intentionally has no model, ONNX, or network dependency.
 */
class NoConfiguredEmbeddingProvider(
    override val activeEmbeddingTarget: ActiveEmbeddingTarget = ActiveEmbeddingTarget(V2EmbeddingProfile.fixed),
) : EmbeddingPort {
    override val providerState: EmbeddingProviderState = EmbeddingProviderState.NOT_CONFIGURED

    override fun embedDocuments(documents: List<String>): List<EmbeddingResult> =
        throw ProviderNotConfiguredException()

    override fun embedQuery(query: String): EmbeddingResult = throw ProviderNotConfiguredException()
}
