package com.github.silbaram.plan2agent.memory.support

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingResult
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderContractViolationException
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderUnavailableException
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Test-only deterministic [EmbeddingPort] fixture. It has no Spring annotations, model loading,
 * or network behavior, so tests can depend on it without changing production provider selection.
 */
class FakeEmbeddingPort(
    override val activeEmbeddingTarget: ActiveEmbeddingTarget = ActiveEmbeddingTarget(V2EmbeddingProfile.fixed),
    providerState: EmbeddingProviderState = EmbeddingProviderState.READY,
) : EmbeddingPort {
    override var providerState: EmbeddingProviderState = providerState

    private val invocations = mutableListOf<FakeEmbeddingRequest>()
    private val configuredEmbeddings = mutableMapOf<FakeEmbeddingRequest, List<Float>>()
    private val configuredFailures = mutableMapOf<FakeEmbeddingMode, FakeEmbeddingFailure>()
    private val beforeEmbedActions = mutableMapOf<FakeEmbeddingMode, () -> Unit>()

    /** Recorded raw port inputs, including the explicit mode used for each invocation. */
    val requests: List<FakeEmbeddingRequest>
        get() = invocations.toList()

    /**
     * Overrides one raw port input with a normalized fixture vector. This is useful when a test
     * must control a ranking exactly instead of relying on synthetic hash-derived vectors.
     */
    fun configureEmbedding(
        mode: FakeEmbeddingMode,
        input: String,
        embedding: List<Float>,
    ): FakeEmbeddingPort {
        configuredEmbeddings[FakeEmbeddingRequest(mode, input)] = normalize(embedding)
        return this
    }

    /** Makes matching port invocations fail until [clearFailure] is called. */
    fun fail(mode: FakeEmbeddingMode, failure: FakeEmbeddingFailure): FakeEmbeddingPort {
        configuredFailures[mode] = failure
        return this
    }

    fun clearFailure(mode: FakeEmbeddingMode): FakeEmbeddingPort {
        configuredFailures.remove(mode)
        return this
    }

    /** Runs once immediately before the next matching provider invocation. */
    fun beforeNextEmbed(mode: FakeEmbeddingMode, action: () -> Unit): FakeEmbeddingPort {
        beforeEmbedActions[mode] = action
        return this
    }

    override fun embedDocuments(documents: List<String>): List<EmbeddingResult> =
        documents.map { document -> embed(FakeEmbeddingMode.DOCUMENT, document) }

    override fun embedQuery(query: String): EmbeddingResult = embed(FakeEmbeddingMode.QUERY, query)

    private fun embed(mode: FakeEmbeddingMode, input: String): EmbeddingResult {
        val request = FakeEmbeddingRequest(mode, input)
        invocations += request
        beforeEmbedActions.remove(mode)?.invoke()

        when (providerState) {
            EmbeddingProviderState.NOT_CONFIGURED -> throw ProviderNotConfiguredException()
            EmbeddingProviderState.INITIALIZING,
            EmbeddingProviderState.UNAVAILABLE,
            -> throw ProviderUnavailableException()
            EmbeddingProviderState.READY -> Unit
        }

        configuredFailures[mode]?.throwException()
        val embedding = configuredEmbeddings[request] ?: deterministicEmbedding(mode, input)
        return EmbeddingResult(target = activeEmbeddingTarget, embedding = Embedding(embedding))
    }

    private fun deterministicEmbedding(mode: FakeEmbeddingMode, input: String): List<Float> {
        val profileInput = when (mode) {
            FakeEmbeddingMode.QUERY -> activeEmbeddingTarget.profile.queryInput(input)
            FakeEmbeddingMode.DOCUMENT -> activeEmbeddingTarget.profile.documentInput(input)
        }
        val seed = "${activeEmbeddingTarget.profile.fingerprint}\u0000$profileInput"
        val raw = FloatArray(activeEmbeddingTarget.profile.dimension)
        var index = 0
        var block = 0

        while (index < raw.size) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$seed\u0000$block".toByteArray(StandardCharsets.UTF_8))
            digest.forEach { value ->
                if (index < raw.size) {
                    raw[index] = ((value.toInt() and 0xff) - 127.5f)
                    index += 1
                }
            }
            block += 1
        }

        return normalize(raw.toList())
    }

    private fun normalize(values: List<Float>): List<Float> {
        require(values.size == activeEmbeddingTarget.profile.dimension) {
            "Fake embedding dimension must match the active profile"
        }
        require(values.all { it.isFinite() }) { "Fake embedding values must be finite" }

        val squaredNorm = values.sumOf { value -> value.toDouble() * value.toDouble() }
        require(squaredNorm.isFinite() && squaredNorm > 0.0) { "Fake embedding norm must be positive" }
        val norm = sqrt(squaredNorm)

        return values.map { value -> (value / norm).toFloat() }
    }

    private fun FakeEmbeddingFailure.throwException(): Nothing = when (this) {
        FakeEmbeddingFailure.RETRYABLE_UNAVAILABLE -> throw ProviderUnavailableException(retryable = true)
        FakeEmbeddingFailure.CONTRACT_VIOLATION -> throw ProviderContractViolationException()
    }

}

enum class FakeEmbeddingMode {
    QUERY,
    DOCUMENT,
}

data class FakeEmbeddingRequest(
    val mode: FakeEmbeddingMode,
    val input: String,
)

enum class FakeEmbeddingFailure {
    RETRYABLE_UNAVAILABLE,
    CONTRACT_VIOLATION,
}

/**
 * Synthetic Korean retrieval fixture with an intentionally controlled score order. It validates
 * retrieval plumbing only; it makes no claim about multilingual embedding or language quality.
 */
data class KoreanRetrievalRankingFixture(
    val query: String = "결제 취소 정책",
    val documentsInExpectedRankOrder: List<String> = listOf(
        "결제 취소 정책은 주문 상세 화면에서 취소 요청을 제출하는 방법을 안내합니다.",
        "배송 조회 정책은 운송장 번호와 현재 배송 상태를 안내합니다.",
    ),
) {
    init {
        require(documentsInExpectedRankOrder.size >= 2) { "Ranking fixture requires at least two documents" }
        require(documentsInExpectedRankOrder.distinct().size == documentsInExpectedRankOrder.size) {
            "Ranking fixture documents must be distinct"
        }
    }

    /** Installs exact, descending cosine scores for [query] and the ranked document list. */
    fun installInto(fake: FakeEmbeddingPort): FakeEmbeddingPort {
        val dimension = fake.activeEmbeddingTarget.profile.dimension
        require(documentsInExpectedRankOrder.size < dimension) { "Ranking fixture exceeds embedding dimensions" }

        fake.configureEmbedding(FakeEmbeddingMode.QUERY, query, oneHotVector(dimension, 0))
        documentsInExpectedRankOrder.forEachIndexed { index, document ->
            val similarity = (documentsInExpectedRankOrder.size - index).toFloat() /
                (documentsInExpectedRankOrder.size + 1).toFloat()
            val contrast = sqrt(1.0 - similarity * similarity).toFloat()
            val vector = FloatArray(dimension)
            vector[0] = similarity
            vector[index + 1] = contrast
            fake.configureEmbedding(FakeEmbeddingMode.DOCUMENT, document, vector.toList())
        }

        return fake
    }

    private fun oneHotVector(dimension: Int, index: Int): List<Float> =
        List(dimension) { if (it == index) 1f else 0f }
}
