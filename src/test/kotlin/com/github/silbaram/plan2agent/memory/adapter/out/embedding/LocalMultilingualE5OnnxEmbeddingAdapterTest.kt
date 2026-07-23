package com.github.silbaram.plan2agent.memory.adapter.out.embedding

import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderContractViolationException
import com.github.silbaram.plan2agent.memory.config.SpringAiTransformersEmbeddingModelFactory
import com.github.silbaram.plan2agent.memory.config.TransformersEmbeddingArtifacts
import com.github.silbaram.plan2agent.memory.config.TransformersEmbeddingModelLoader
import com.github.silbaram.plan2agent.memory.config.TransformersEmbeddingModelSession
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.charset.StandardCharsets

class LocalMultilingualE5OnnxEmbeddingAdapterTest {
    @Test
    fun `prefixes raw queries and documents exactly once before runtime invocation`() {
        val runtime = RecordingRuntime(validEmbedding(), validEmbedding(), validEmbedding())
        val adapter = LocalMultilingualE5OnnxEmbeddingAdapter(runtime)

        adapter.embedQuery("query: already prefixed")
        adapter.embedDocuments(listOf("passage: already prefixed", "본문"))

        assertThat(runtime.inputs).hasSize(3)
        assertThat(runtime.inputs[0].toByteArray(StandardCharsets.UTF_8))
            .containsExactly(*"query: query: already prefixed".toByteArray(StandardCharsets.UTF_8))
        assertThat(runtime.inputs[1].toByteArray(StandardCharsets.UTF_8))
            .containsExactly(*"passage: passage: already prefixed".toByteArray(StandardCharsets.UTF_8))
        assertThat(runtime.inputs[2].toByteArray(StandardCharsets.UTF_8))
            .containsExactly(*"passage: 본문".toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    fun `passes the fixed tokenizer contract to the Spring AI model loader`() {
        var capturedOptions: Map<String, String>? = null
        val factory = SpringAiTransformersEmbeddingModelFactory(
            TransformersEmbeddingModelLoader { _, options ->
                capturedOptions = options
                TransformersEmbeddingModelSession { V2EmbeddingProfile.fixed.dimension }
            },
        )

        factory.create(
            TransformersEmbeddingArtifacts(
                modelArtifactUri = URI.create("file:/fixtures/model.onnx"),
                tokenizerArtifactUri = URI.create("file:/fixtures/tokenizer.json"),
                modelOutputName = V2EmbeddingProfile.fixed.modelOutputName,
            ),
        )

        assertThat(capturedOptions).containsExactlyEntriesOf(
            mapOf(
                "addSpecialTokens" to "true",
                "modelMaxLength" to "512",
                "maxLength" to "512",
                "padding" to "true",
                "truncation" to "true",
            ),
        )
    }

    @Test
    fun `normalizes the attention-mask pooled fixture without including padded tokens`() {
        val pooledFixture = attentionMaskedMean(
            tokenEmbeddings = listOf(
                floatArrayOf(2f, 2f),
                floatArrayOf(4f, 6f),
                floatArrayOf(100f, 200f),
            ),
            attentionMask = intArrayOf(1, 1, 0),
        )
        val rawEmbedding = FloatArray(V2EmbeddingProfile.fixed.dimension).also {
            it[0] = pooledFixture[0]
            it[1] = pooledFixture[1]
        }
        val adapter = LocalMultilingualE5OnnxEmbeddingAdapter(RecordingRuntime(rawEmbedding))

        val result = adapter.embedQuery("masked pooling fixture")

        assertThat(pooledFixture).containsExactly(3f, 4f)
        assertThat(result.embedding.values.take(2)).containsExactly(0.6f, 0.8f)
        assertThat(result.embedding.values.sumOf { value -> value.toDouble() * value.toDouble() })
            .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001))
    }

    @Test
    fun `rejects vectors outside the fixed 384 dimensions`() {
        listOf(383, 385).forEach { dimension ->
            val adapter = LocalMultilingualE5OnnxEmbeddingAdapter(
                RecordingRuntime(FloatArray(dimension) { 1f }),
            )

            assertThatThrownBy { adapter.embedQuery("dimension $dimension") }
                .isInstanceOf(ProviderContractViolationException::class.java)
        }
    }

    @Test
    fun `rejects zero norm and non-finite vectors`() {
        val invalidEmbeddings = listOf(
            FloatArray(V2EmbeddingProfile.fixed.dimension),
            validEmbedding().also { it[0] = Float.NaN },
            validEmbedding().also { it[0] = Float.POSITIVE_INFINITY },
        )

        invalidEmbeddings.forEach { invalidEmbedding ->
            val adapter = LocalMultilingualE5OnnxEmbeddingAdapter(RecordingRuntime(invalidEmbedding))

            assertThatThrownBy { adapter.embedQuery("invalid vector") }
                .isInstanceOf(ProviderContractViolationException::class.java)
        }
    }

    private fun attentionMaskedMean(
        tokenEmbeddings: List<FloatArray>,
        attentionMask: IntArray,
    ): FloatArray {
        require(tokenEmbeddings.size == attentionMask.size)
        val dimensions = tokenEmbeddings.first().size
        val summed = FloatArray(dimensions)
        var includedTokens = 0

        tokenEmbeddings.zip(attentionMask.asList()).forEach { (tokenEmbedding, mask) ->
            if (mask == 1) {
                tokenEmbedding.forEachIndexed { index, value -> summed[index] += value }
                includedTokens += 1
            }
        }

        return summed.map { value -> value / includedTokens }.toFloatArray()
    }

    private fun validEmbedding(): FloatArray =
        FloatArray(V2EmbeddingProfile.fixed.dimension).also {
            it[0] = 3f
            it[1] = 4f
        }

    private class RecordingRuntime(
        private vararg val embeddings: FloatArray,
    ) : LocalEmbeddingRuntime {
        override val providerState: EmbeddingProviderState = EmbeddingProviderState.READY
        override val activeEmbeddingSetId: EmbeddingSetId = EmbeddingSetId("a7c580a5-72c9-4c62-a1f1-018a7e294dd6")
        val inputs = mutableListOf<String>()

        override fun embed(input: String): FloatArray = embeddings[inputs.size].also { inputs += input }
    }
}
