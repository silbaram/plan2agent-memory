package com.github.silbaram.plan2agent.memory.adapter.out.embedding

import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.config.EmbeddingProperties
import com.github.silbaram.plan2agent.memory.config.EmbeddingProviderKind
import com.github.silbaram.plan2agent.memory.config.FileSystemTransformersArtifactVerifier
import com.github.silbaram.plan2agent.memory.config.SpringAiTransformersEmbeddingModelFactory
import com.github.silbaram.plan2agent.memory.config.TransformersEmbeddingArtifacts
import com.github.silbaram.plan2agent.memory.config.TransformersEmbeddingModelSession
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import com.github.silbaram.plan2agent.memory.support.RawOnnxEmbedding
import com.github.silbaram.plan2agent.memory.support.VerifiedOnnxE5Probe
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.math.sqrt

@Tag("onnx-verification")
class PinnedMultilingualE5OnnxVerificationTest {
    private val profile = V2EmbeddingProfile.fixed

    @Test
    fun `verified local Korean ONNX model preserves E5 pooling normalization and retrieval contract`() {
        val artifacts = verifiedOperatorArtifacts()
        val springAiSession = SpringAiTransformersEmbeddingModelFactory().create(artifacts)
        val runtime = RecordingSpringAiRuntime(springAiSession)
        val adapter = LocalMultilingualE5OnnxEmbeddingAdapter(runtime)
        val query = "배송 현황을 확인하는 방법"
        val matchingPassage = "배송 현황을 확인하는 방법"

        val queryEmbedding = adapter.embedQuery(query)
        val matchingPassageEmbedding = adapter.embedDocuments(listOf(matchingPassage)).single()

        assertThat(runtime.inputs).containsExactly(
            profile.queryInput(query),
            profile.documentInput(matchingPassage),
        )

        VerifiedOnnxE5Probe(artifacts).use { probe ->
            assertSpringAiMatchesIndependentAttentionMaskedMean(
                springAiEmbedding = runtime.rawEmbeddings[0],
                rawOnnxEmbedding = probe.rawEmbedding(runtime.inputs[0]),
            )
            assertSpringAiMatchesIndependentAttentionMaskedMean(
                springAiEmbedding = runtime.rawEmbeddings[1],
                rawOnnxEmbedding = probe.rawEmbedding(runtime.inputs[1]),
            )
        }

        assertNormalizedEmbedding(queryEmbedding.embedding.values)
        assertNormalizedEmbedding(matchingPassageEmbedding.embedding.values)
        assertExplicitL2Normalization(queryEmbedding.embedding.values, runtime.rawEmbeddings[0])
        assertExplicitL2Normalization(matchingPassageEmbedding.embedding.values, runtime.rawEmbeddings[1])

        val candidates = listOf(
            matchingPassage,
            "비밀번호를 변경하려면 계정 설정 메뉴를 사용하세요.",
            "환불 처리 기간은 결제 수단에 따라 달라질 수 있습니다.",
        )
        val topTwo = adapter.embedDocuments(candidates)
            .mapIndexed { index, result -> index to dotProduct(queryEmbedding.embedding.values, result.embedding.values) }
            .sortedByDescending { (_, score) -> score }
            .take(2)

        assertThat(topTwo).hasSize(2)
        assertThat(topTwo.first().first).isEqualTo(0)
        assertThat(topTwo.map { it.second }).allSatisfy { score -> assertThat(score).isFinite() }
    }

    private fun verifiedOperatorArtifacts(): TransformersEmbeddingArtifacts =
        FileSystemTransformersArtifactVerifier().verify(
            EmbeddingProperties(
                provider = EmbeddingProviderKind.TRANSFORMERS,
                modelArtifactUri = requiredArtifactUri("P2A_ONNX_MODEL_URI"),
                tokenizerArtifactUri = requiredArtifactUri("P2A_ONNX_TOKENIZER_URI"),
            ),
            profile,
        )

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: error("ONNX verification environment is not configured")

    private fun requiredArtifactUri(name: String): URI =
        runCatching { URI.create(requiredEnvironment(name)) }
            .getOrElse { error("ONNX verification artifact URI is invalid") }

    private fun assertSpringAiMatchesIndependentAttentionMaskedMean(
        springAiEmbedding: FloatArray,
        rawOnnxEmbedding: RawOnnxEmbedding,
    ) {
        val independentlyPooled = independentlyAttentionMaskMean(rawOnnxEmbedding)

        rawOnnxEmbedding.tokenEmbeddings.forEach { tokenEmbedding ->
            assertThat(tokenEmbedding).hasSize(profile.dimension)
        }
        rawOnnxEmbedding.attentionMask.forEach { mask ->
            assertThat(mask).isIn(0L, 1L)
        }
        assertThat(rawOnnxEmbedding.attentionMask).contains(1L)
        assertThat(springAiEmbedding).hasSize(profile.dimension)
        assertThat(independentlyPooled).hasSize(profile.dimension)
        springAiEmbedding.indices.forEach { index ->
            assertThat(springAiEmbedding[index]).isCloseTo(independentlyPooled[index], offset(0.00001f))
        }
    }

    private fun independentlyAttentionMaskMean(rawOnnxEmbedding: RawOnnxEmbedding): FloatArray {
        require(rawOnnxEmbedding.tokenEmbeddings.size == rawOnnxEmbedding.attentionMask.size)
        val pooled = FloatArray(profile.dimension)
        var includedTokens = 0L

        rawOnnxEmbedding.tokenEmbeddings.forEachIndexed { tokenIndex, tokenEmbedding ->
            val mask = rawOnnxEmbedding.attentionMask[tokenIndex]
            if (mask == 1L) {
                tokenEmbedding.forEachIndexed { index, value -> pooled[index] += value }
                includedTokens += 1
            }
        }
        check(includedTokens > 0L)
        return pooled.map { value -> value / includedTokens }.toFloatArray()
    }

    private fun assertNormalizedEmbedding(values: List<Float>) {
        assertThat(values).hasSize(profile.dimension)
        assertThat(values).allSatisfy { value -> assertThat(value).isFinite() }
        assertThat(l2Norm(values)).isCloseTo(1.0, offset(0.00001))
    }

    private fun assertExplicitL2Normalization(normalized: List<Float>, raw: FloatArray) {
        val rawNorm = sqrt(raw.sumOf { value -> value.toDouble() * value.toDouble() })

        normalized.indices.forEach { index ->
            assertThat(normalized[index]).isCloseTo((raw[index] / rawNorm).toFloat(), offset(0.00001f))
        }
    }

    private fun l2Norm(values: List<Float>): Double =
        sqrt(values.sumOf { value -> value.toDouble() * value.toDouble() })

    private fun dotProduct(left: List<Float>, right: List<Float>): Double =
        left.zip(right).sumOf { (leftValue, rightValue) -> leftValue.toDouble() * rightValue.toDouble() }

    private class RecordingSpringAiRuntime(
        private val session: TransformersEmbeddingModelSession,
    ) : LocalEmbeddingRuntime {
        override val providerState: EmbeddingProviderState = EmbeddingProviderState.READY
        override val activeEmbeddingSetId: EmbeddingSetId = EmbeddingSetId("781c0cbb-56af-4f70-943c-61d2f5f4b8e8")
        val inputs = mutableListOf<String>()
        val rawEmbeddings = mutableListOf<FloatArray>()

        override fun embed(input: String): FloatArray = session.embed(input).also { rawEmbedding ->
            inputs += input
            rawEmbeddings += rawEmbedding
        }
    }
}
