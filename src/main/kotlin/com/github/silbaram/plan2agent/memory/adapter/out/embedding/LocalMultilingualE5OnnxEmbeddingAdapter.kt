package com.github.silbaram.plan2agent.memory.adapter.out.embedding

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingResult
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderContractViolationException
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import kotlin.math.sqrt

/**
 * Application-owned adapter for the fixed multilingual-e5 local ONNX vector-space contract.
 *
 * Spring AI tokenizes with the fixed contract and returns an attention-mask mean-pooled vector;
 * this adapter owns E5 prefixing, output validation, and L2 normalization before crossing the
 * application port.
 */
class LocalMultilingualE5OnnxEmbeddingAdapter(
    private val runtime: LocalEmbeddingRuntime,
    private val profile: V2EmbeddingProfile = V2EmbeddingProfile.fixed,
) : EmbeddingPort {
    override val providerState: EmbeddingProviderState
        get() = runtime.providerState

    override val activeEmbeddingTarget: ActiveEmbeddingTarget
        get() = ActiveEmbeddingTarget(profile, runtime.activeEmbeddingSetId)

    override fun embedDocuments(documents: List<String>): List<EmbeddingResult> =
        documents.map { document -> embed(profile.documentInput(document)) }

    override fun embedQuery(query: String): EmbeddingResult = embed(profile.queryInput(query))

    private fun embed(input: String): EmbeddingResult {
        val rawEmbedding = runtime.embed(input)
        val normalized = normalize(rawEmbedding)
        return EmbeddingResult(
            target = activeEmbeddingTarget,
            embedding = Embedding(normalized),
        )
    }

    private fun normalize(rawEmbedding: FloatArray): List<Float> {
        if (rawEmbedding.size != profile.dimension || rawEmbedding.any { !it.isFinite() }) {
            throw ProviderContractViolationException()
        }

        val squaredNorm = rawEmbedding.sumOf { value -> value.toDouble() * value.toDouble() }
        if (!squaredNorm.isFinite() || squaredNorm <= 0.0) {
            throw ProviderContractViolationException()
        }

        val norm = sqrt(squaredNorm)
        if (!norm.isFinite() || norm <= 0.0) {
            throw ProviderContractViolationException()
        }

        return rawEmbedding.map { value ->
            val normalizedValue = (value / norm).toFloat()
            if (!normalizedValue.isFinite()) {
                throw ProviderContractViolationException()
            }
            normalizedValue
        }
    }
}

/** Narrow runtime seam so adapter tests never need local model artifacts or a native runtime. */
interface LocalEmbeddingRuntime {
    val providerState: EmbeddingProviderState
    val activeEmbeddingSetId: EmbeddingSetId?

    fun embed(input: String): FloatArray
}
