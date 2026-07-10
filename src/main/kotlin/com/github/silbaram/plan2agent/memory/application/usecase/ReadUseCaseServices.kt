package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactsUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactGraphNodesUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.TraceArtifactGraphUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.HybridSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.KeywordSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.VectorSearchUseCase
import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactQueryPort
import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactGraphStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.KeywordSearchPort
import com.github.silbaram.plan2agent.memory.application.port.out.VectorSearchPort
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
import com.github.silbaram.plan2agent.memory.domain.ArtifactNode
import com.github.silbaram.plan2agent.memory.domain.ArtifactTrace
import com.github.silbaram.plan2agent.memory.domain.HybridSearchArm
import com.github.silbaram.plan2agent.memory.domain.HybridSearchMatch
import com.github.silbaram.plan2agent.memory.domain.KeywordSearchMatch
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.util.Base64

@Service
class ReadUseCaseService(
    private val artifactQueryPort: ArtifactQueryPort,
    private val keywordSearchPort: KeywordSearchPort,
    private val vectorSearchPort: VectorSearchPort,
    private val artifactGraphStore: ArtifactGraphStorePort,
) : FindArtifactsUseCase,
    KeywordSearchUseCase,
    VectorSearchUseCase,
    HybridSearchUseCase,
    FindArtifactGraphNodesUseCase,
    TraceArtifactGraphUseCase {


    @Transactional(readOnly = true)
    override fun findGraphNodes(query: GraphNodeSearchQuery): List<ArtifactNode> {
        validateLimit(query.limit, "GraphNodeSearchQuery")
        return artifactGraphStore.findNodes(query)
    }

    @Transactional(readOnly = true)
    override fun traceGraph(query: GraphTraceQuery): ArtifactTrace = artifactGraphStore.trace(query)

    @Transactional(readOnly = true)
    override fun findArtifacts(query: FindArtifactsQuery): PagedResult<ArtifactSummary> {
        validateLimit(query.limit, "FindArtifactsQuery")
        validateOptionalSourcePath(query.sourcePath, "FindArtifactsQuery")
        validateOptionalCursor(query.cursor, "FindArtifactsQuery")
        return artifactQueryPort.findArtifacts(query)
    }

    @Transactional(readOnly = true)
    override fun keywordSearch(query: KeywordSearchQuery): PagedResult<KeywordSearchMatch> {
        require(query.query.isNotBlank()) { "KeywordSearchQuery query must not be blank" }
        validateLimit(query.limit, "KeywordSearchQuery")
        validateOptionalSourcePath(query.sourcePath, "KeywordSearchQuery")
        validateMetadataFilters(query.metadataFilters, "KeywordSearchQuery")
        validateOptionalCursor(query.cursor, "KeywordSearchQuery")
        return keywordSearchPort.search(query)
    }

    @Transactional(readOnly = true)
    override fun vectorSearch(query: VectorSearchQuery): PagedResult<VectorSearchMatch> {
        require(query.embedding.values.isNotEmpty()) { "VectorSearchQuery embedding must not be empty" }
        require(query.embedding.values.all { it.isFinite() }) { "VectorSearchQuery embedding values must be finite" }
        require(query.embeddingModel.isNotBlank()) { "VectorSearchQuery embeddingModel must not be blank" }
        require(query.embeddingDimension > 0) { "VectorSearchQuery embeddingDimension must be positive" }
        require(query.embedding.values.size == query.embeddingDimension) {
            "VectorSearchQuery embeddingDimension must match embedding size"
        }
        require(query.embeddingVersion.isNotBlank()) { "VectorSearchQuery embeddingVersion must not be blank" }
        validateLimit(query.limit, "VectorSearchQuery")
        validateOptionalSourcePath(query.sourcePath, "VectorSearchQuery")
        validateMetadataFilters(query.metadataFilters, "VectorSearchQuery")
        validateOptionalCursor(query.cursor, "VectorSearchQuery")
        return vectorSearchPort.search(query)
    }

    @Transactional(readOnly = true)
    override fun hybridSearch(query: HybridSearchQuery): PagedResult<HybridSearchMatch> {
        require(query.query.isNotBlank()) { "HybridSearchQuery query must not be blank" }
        require(query.embedding.values.isNotEmpty()) { "HybridSearchQuery embedding must not be empty" }
        require(query.embedding.values.all { it.isFinite() }) { "HybridSearchQuery embedding values must be finite" }
        require(query.embeddingModel.isNotBlank()) { "HybridSearchQuery embeddingModel must not be blank" }
        require(query.embeddingDimension > 0) { "HybridSearchQuery embeddingDimension must be positive" }
        require(query.embedding.values.size == query.embeddingDimension) {
            "HybridSearchQuery embeddingDimension must match embedding size"
        }
        require(query.embeddingVersion.isNotBlank()) { "HybridSearchQuery embeddingVersion must not be blank" }
        require(query.rrfK > 0) { "HybridSearchQuery rrfK must be positive" }
        require(query.candidateLimit >= query.limit) {
            "HybridSearchQuery candidateLimit must be greater than or equal to limit"
        }
        validateLimit(query.limit, "HybridSearchQuery")
        validateLimit(query.candidateLimit, "HybridSearchQuery candidate")
        validateOptionalSourcePath(query.sourcePath, "HybridSearchQuery")
        validateMetadataFilters(query.metadataFilters, "HybridSearchQuery")
        validateOptionalCursor(query.cursor, "HybridSearchQuery")

        val keywordMatches = keywordSearchPort.search(
            KeywordSearchQuery(
                query = query.query,
                projectId = query.projectId,
                iterationId = query.iterationId,
                artifactType = query.artifactType,
                sourcePath = query.sourcePath,
                taskId = query.taskId,
                runId = query.runId,
                metadataFilters = query.metadataFilters,
                limit = query.candidateLimit,
            ),
        ).items
        val vectorMatches = vectorSearchPort.search(
            VectorSearchQuery(
                embedding = query.embedding,
                embeddingModel = query.embeddingModel,
                embeddingDimension = query.embeddingDimension,
                embeddingVersion = query.embeddingVersion,
                distanceMetric = query.distanceMetric,
                projectId = query.projectId,
                iterationId = query.iterationId,
                artifactType = query.artifactType,
                sourcePath = query.sourcePath,
                taskId = query.taskId,
                runId = query.runId,
                metadataFilters = query.metadataFilters,
                limit = query.candidateLimit,
            ),
        ).items

        val fused = fuseByReciprocalRank(keywordMatches, vectorMatches, query.rrfK)
        val afterCursor = query.cursor?.let(::decodeHybridCursor)
            ?.let { cursor -> fused.filter { it.isAfter(cursor) } }
            ?: fused
        val pageItems = afterCursor.take(query.limit)
        val nextCursor = if (afterCursor.size > query.limit) {
            encodeHybridCursor(requireNotNull(pageItems.lastOrNull()).toHybridCursor())
        } else {
            null
        }
        return PagedResult(items = pageItems, nextCursor = nextCursor)
    }

    private fun fuseByReciprocalRank(
        keywordMatches: List<KeywordSearchMatch>,
        vectorMatches: List<VectorSearchMatch>,
        rrfK: Int,
    ): List<HybridSearchMatch> {
        val candidates = linkedMapOf<String, FusionCandidate>()
        keywordMatches.forEachIndexed { index, match ->
            candidates.getOrPut(match.searchKey()) { FusionCandidate(stableKey = match.searchKey()) }
                .keyword = RankedKeyword(match, index + 1)
        }
        vectorMatches.forEachIndexed { index, match ->
            candidates.getOrPut(match.searchKey()) { FusionCandidate(stableKey = match.searchKey()) }
                .vector = RankedVector(match, index + 1)
        }
        return candidates.values
            .map { it.toHybridSearchMatch(rrfK) }
            .sortedWith(
                compareByDescending<HybridSearchMatch> { it.score }
                    .thenBy { it.bestRank() }
                    .thenBy { it.stableKey() },
            )
    }

    private fun validateLimit(limit: Int, label: String) {
        require(limit > 0) { "$label limit must be positive" }
    }

    private fun validateOptionalSourcePath(sourcePath: String?, label: String) {
        sourcePath?.let {
            require(it.isNotBlank()) { "$label sourcePath must not be blank when supplied" }
        }
    }

    private fun validateMetadataFilters(metadataFilters: Map<String, String>, label: String) {
        require(metadataFilters.keys.all { it.isNotBlank() }) { "$label metadata filter keys must not be blank" }
        require(metadataFilters.values.all { it.isNotBlank() }) { "$label metadata filter values must not be blank" }
    }

    private fun validateOptionalCursor(cursor: String?, label: String) {
        cursor?.let {
            require(it.isNotBlank()) { "$label cursor must not be blank when supplied" }
        }
    }

    private fun encodeHybridCursor(cursor: HybridCursor): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                listOf(HYBRID_CURSOR_KIND, cursor.score.toString(), cursor.bestRank.toString(), cursor.stableKey)
                    .joinToString("|")
                    .toByteArray(StandardCharsets.UTF_8),
            )

    private fun decodeHybridCursor(value: String): HybridCursor {
        val decoded = try {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("cursor has invalid format")
        }
        val parts = decoded.split('|', limit = 4)
        require(parts.size == 4 && parts[0] == HYBRID_CURSOR_KIND) { "cursor has invalid format" }
        return try {
            val cursor = HybridCursor(
                score = parts[1].toDouble(),
                bestRank = parts[2].toInt(),
                stableKey = parts[3],
            )
            require(cursor.score.isFinite() && cursor.bestRank > 0 && cursor.stableKey.isNotBlank()) {
                "cursor has invalid format"
            }
            cursor
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("cursor has invalid format")
        }
    }
}

private data class RankedKeyword(
    val match: KeywordSearchMatch,
    val rank: Int,
)

private data class RankedVector(
    val match: VectorSearchMatch,
    val rank: Int,
)

private data class FusionCandidate(
    val stableKey: String,
    var keyword: RankedKeyword? = null,
    var vector: RankedVector? = null,
)

private data class HybridCursor(
    val score: Double,
    val bestRank: Int,
    val stableKey: String,
)

private fun FusionCandidate.toHybridSearchMatch(rrfK: Int): HybridSearchMatch {
    val keywordMatch = keyword?.match
    val vectorMatch = vector?.match
    require(keywordMatch != null || vectorMatch != null) { "Fusion candidate must contain at least one arm" }
    val keywordScore = keyword?.let { 1.0 / (rrfK + it.rank).toDouble() } ?: 0.0
    val vectorScore = vector?.let { 1.0 / (rrfK + it.rank).toDouble() } ?: 0.0
    return HybridSearchMatch(
        chunkId = keywordMatch?.chunkId ?: vectorMatch?.chunkId,
        documentId = keywordMatch?.documentId ?: vectorMatch?.documentId,
        projectId = keywordMatch?.projectId ?: requireNotNull(vectorMatch).projectId,
        iterationId = keywordMatch?.iterationId ?: vectorMatch?.iterationId,
        artifactType = keywordMatch?.artifactType ?: requireNotNull(vectorMatch).artifactType,
        sourcePath = keywordMatch?.sourcePath ?: vectorMatch?.sourcePath,
        chunkIndex = keywordMatch?.chunkIndex ?: vectorMatch?.chunkIndex,
        content = keywordMatch?.content ?: requireNotNull(vectorMatch).content,
        score = keywordScore + vectorScore,
        matchReason = when {
            keyword != null && vector != null -> "hybrid.keyword+vector"
            keyword != null -> "hybrid.keyword"
            else -> "hybrid.vector"
        },
        keyword = keyword?.let { HybridSearchArm(rank = it.rank, score = it.match.score) },
        vector = vector?.let { HybridSearchArm(rank = it.rank, score = it.match.score) },
        metadata = (vector?.match?.metadata ?: emptyMap()) + (keyword?.match?.metadata ?: emptyMap()),
        sourceReference = keyword?.match?.sourceReference ?: vector?.match?.sourceReference,
    )
}

private fun KeywordSearchMatch.searchKey(): String =
    chunkId?.value?.let { "chunk:$it" } ?: documentId?.value?.let { "document:$it" } ?: "content:${content.hashCode()}"

private fun VectorSearchMatch.searchKey(): String =
    chunkId?.value?.let { "chunk:$it" } ?: documentId?.value?.let { "document:$it" } ?: "content:${content.hashCode()}"

private fun HybridSearchMatch.stableKey(): String =
    chunkId?.value?.let { "chunk:$it" } ?: documentId?.value?.let { "document:$it" } ?: "content:${content.hashCode()}"

private fun HybridSearchMatch.bestRank(): Int =
    listOfNotNull(keyword?.rank, vector?.rank).minOrNull() ?: Int.MAX_VALUE

private fun HybridSearchMatch.toHybridCursor(): HybridCursor =
    HybridCursor(score = score, bestRank = bestRank(), stableKey = stableKey())

private fun HybridSearchMatch.isAfter(cursor: HybridCursor): Boolean =
    score < cursor.score ||
        (score == cursor.score && bestRank() > cursor.bestRank) ||
        (score == cursor.score && bestRank() == cursor.bestRank && stableKey() > cursor.stableKey)

private const val HYBRID_CURSOR_KIND = "hybrid.v1"
