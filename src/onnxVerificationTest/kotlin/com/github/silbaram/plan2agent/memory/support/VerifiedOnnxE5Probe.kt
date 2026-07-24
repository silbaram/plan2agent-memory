package com.github.silbaram.plan2agent.memory.support

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.github.silbaram.plan2agent.memory.config.TransformersEmbeddingArtifacts
import com.github.silbaram.plan2agent.memory.config.TransformersTokenizerOptions
import java.nio.file.Path

/**
 * Test-only direct ONNX probe used to independently validate Spring AI's pooling result.
 *
 * Callers supply [TransformersEmbeddingArtifacts] only after the application verifier has
 * accepted the operator-provided file URIs and pinned checksums.
 */
class VerifiedOnnxE5Probe(
    private val artifacts: TransformersEmbeddingArtifacts,
) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val tokenizer = HuggingFaceTokenizer.newInstance(
        Path.of(artifacts.tokenizerArtifactUri),
        TransformersTokenizerOptions.fixed,
    )
    private val session = OrtSession.SessionOptions().use { options ->
        environment.createSession(Path.of(artifacts.modelArtifactUri).toString(), options)
    }

    fun rawEmbedding(input: String): RawOnnxEmbedding {
        val encoding = tokenizer.batchEncode(listOf(input)).single()
        val tensors = linkedMapOf(
            INPUT_IDS to OnnxTensor.createTensor(environment, arrayOf(encoding.ids)),
            ATTENTION_MASK to OnnxTensor.createTensor(environment, arrayOf(encoding.attentionMask)),
            TOKEN_TYPE_IDS to OnnxTensor.createTensor(environment, arrayOf(encoding.typeIds)),
        ).filterKeys(session.inputNames::contains)

        try {
            session.run(tensors).use { result ->
                @Suppress("UNCHECKED_CAST")
                val tokenEmbeddings = result.get(artifacts.modelOutputName)
                    .orElseThrow { IllegalStateException("Pinned ONNX model did not expose its configured output") }
                    .value as Array<Array<FloatArray>>
                return RawOnnxEmbedding(
                    tokenEmbeddings = tokenEmbeddings.single().map(FloatArray::copyOf).toTypedArray(),
                    attentionMask = encoding.attentionMask.copyOf(),
                )
            }
        } finally {
            tensors.values.forEach(OnnxTensor::close)
        }
    }

    override fun close() {
        try {
            session.close()
        } finally {
            tokenizer.close()
        }
    }

    companion object {
        private const val INPUT_IDS = "input_ids"
        private const val ATTENTION_MASK = "attention_mask"
        private const val TOKEN_TYPE_IDS = "token_type_ids"
    }
}

data class RawOnnxEmbedding(
    val tokenEmbeddings: Array<FloatArray>,
    val attentionMask: LongArray,
)
