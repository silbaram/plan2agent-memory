package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.DEFAULT_HYBRID_CANDIDATE_LIMIT
import com.github.silbaram.plan2agent.memory.application.usecase.DEFAULT_RRF_K
import com.github.silbaram.plan2agent.memory.application.usecase.HybridSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.GraphNodeSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.GraphTraceDirection
import com.github.silbaram.plan2agent.memory.application.usecase.GraphTraceQuery
import com.github.silbaram.plan2agent.memory.application.usecase.KeywordSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.HybridSearchArm
import com.github.silbaram.plan2agent.memory.domain.HybridSearchMatch
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.KeywordSearchMatch
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.SourceRunId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch
import java.time.Instant

private const val DEFAULT_ARTIFACT_LIMIT = 50
private const val DEFAULT_SEARCH_LIMIT = 20

data class ArtifactLookupRequest(
    val projectId: String? = null,
    val iterationId: String? = null,
    val sourceProjectId: String? = null,
    val sourceIterationId: String? = null,
    val sourceDocumentId: String? = null,
    val sourceTaskGraphId: String? = null,
    val sourceTaskId: String? = null,
    val sourceRunId: String? = null,
    val artifactType: String? = null,
    val sourcePath: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val contentHash: String? = null,
    val sourceReferenceCanonicalServerId: String? = null,
    val sourceReferenceUri: String? = null,
    val limit: Int? = null,
    val cursor: String? = null,
)

data class KeywordSearchRequest(
    val q: String? = null,
    val projectId: String? = null,
    val iterationId: String? = null,
    val artifactType: String? = null,
    val sourcePath: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val metadataFilters: Map<String, String> = emptyMap(),
    val limit: Int? = null,
    val cursor: String? = null,
)

data class VectorSearchRequest(
    val embedding: List<Float>? = null,
    val embeddingModel: String? = null,
    val embeddingDimension: Int? = null,
    val embeddingVersion: String? = null,
    val distanceMetric: String? = null,
    val projectId: String? = null,
    val iterationId: String? = null,
    val artifactType: String? = null,
    val sourcePath: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val metadataFilters: Map<String, String> = emptyMap(),
    val limit: Int? = null,
    val cursor: String? = null,
)

data class HybridSearchRequest(
    val q: String? = null,
    val embedding: List<Float>? = null,
    val embeddingModel: String? = null,
    val embeddingDimension: Int? = null,
    val embeddingVersion: String? = null,
    val distanceMetric: String? = null,
    val projectId: String? = null,
    val iterationId: String? = null,
    val artifactType: String? = null,
    val sourcePath: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val metadataFilters: Map<String, String> = emptyMap(),
    val rrfK: Int? = null,
    val candidateLimit: Int? = null,
    val limit: Int? = null,
    val cursor: String? = null,
)

data class PagedResponse<T>(
    val items: List<T>,
    val nextCursor: String? = null,
)

data class SourceIdsResponse(
    val sourceProjectId: String? = null,
    val sourceIterationId: String? = null,
    val sourceDocumentId: String? = null,
    val sourceTaskGraphId: String? = null,
    val sourceTaskId: String? = null,
    val sourceRunId: String? = null,
    val sourceChunkId: String? = null,
)

data class ArtifactLookupResponse(
    val artifactType: String,
    val artifactId: String,
    val projectId: String,
    val iterationId: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val sourcePath: String? = null,
    val title: String,
    val contentHash: String? = null,
    val snapshotVersion: Int? = null,
    val lineage: ArtifactLineageResponse,
    val sourceIds: SourceIdsResponse,
    val sourceReference: SourceReferenceResponse? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class SearchLineageResponse(
    val projectId: String,
    val iterationId: String? = null,
    val documentId: String? = null,
    val chunkId: String? = null,
    val sourcePath: String? = null,
    val chunkIndex: Int? = null,
)

data class SearchCitationResponse(
    val lineage: SearchLineageResponse,
    val sourceIds: SourceIdsResponse,
    val sourceReference: SourceReferenceResponse? = null,
)

data class KeywordSearchResponse(
    val chunkId: String? = null,
    val documentId: String? = null,
    val projectId: String,
    val iterationId: String? = null,
    val artifactType: String,
    val sourcePath: String? = null,
    val chunkIndex: Int? = null,
    val content: String,
    val score: Double,
    val matchReason: String,
    val lineage: SearchLineageResponse,
    val sourceIds: SourceIdsResponse,
    val sourceReference: SourceReferenceResponse? = null,
    val citation: SearchCitationResponse,
    val metadata: Map<String, String> = emptyMap(),
)

data class VectorSearchResponse(
    val chunkId: String? = null,
    val documentId: String? = null,
    val projectId: String,
    val iterationId: String? = null,
    val artifactType: String,
    val sourcePath: String? = null,
    val chunkIndex: Int? = null,
    val content: String,
    val score: Double,
    val distanceMetric: String,
    val embeddingModel: String,
    val embeddingVersion: String,
    val lineage: SearchLineageResponse,
    val sourceIds: SourceIdsResponse,
    val sourceReference: SourceReferenceResponse? = null,
    val citation: SearchCitationResponse,
    val metadata: Map<String, String> = emptyMap(),
)

data class HybridSearchArmResponse(
    val rank: Int,
    val score: Double,
)

data class HybridSearchResponse(
    val chunkId: String? = null,
    val documentId: String? = null,
    val projectId: String,
    val iterationId: String? = null,
    val artifactType: String,
    val sourcePath: String? = null,
    val chunkIndex: Int? = null,
    val content: String,
    val score: Double,
    val matchReason: String,
    val keyword: HybridSearchArmResponse? = null,
    val vector: HybridSearchArmResponse? = null,
    val lineage: SearchLineageResponse,
    val sourceIds: SourceIdsResponse,
    val sourceReference: SourceReferenceResponse? = null,
    val citation: SearchCitationResponse,
    val metadata: Map<String, String> = emptyMap(),
)

data class HealthResponse(
    val status: String,
    val timestamp: Instant = Instant.now(),
)

fun ArtifactLookupRequest.toQuery(): FindArtifactsQuery =
    FindArtifactsQuery(
        projectId = projectId.toOptionalId(::ProjectId),
        iterationId = iterationId.toOptionalId(::IterationId),
        sourceProjectId = sourceProjectId.toOptionalId(::SourceProjectId),
        sourceIterationId = sourceIterationId.toOptionalId(::SourceIterationId),
        sourceDocumentId = sourceDocumentId.toOptionalId(::SourceDocumentId),
        sourceTaskGraphId = sourceTaskGraphId.toOptionalId(::SourceTaskGraphId),
        sourceTaskId = sourceTaskId.toOptionalId(::SourceTaskId),
        sourceRunId = sourceRunId.toOptionalId(::SourceRunId),
        artifactType = parseOptionalEnum<ArtifactType>(artifactType, "artifactType"),
        sourcePath = sourcePath?.trim()?.takeIf(String::isNotEmpty),
        taskId = taskId.toOptionalId(::TaskId),
        runId = runId.toOptionalId(::RunId),
        contentHash = contentHash.toOptionalId(::ContentHash),
        sourceReference = toSourceReferenceFilter(),
        limit = limit ?: DEFAULT_ARTIFACT_LIMIT,
        cursor = cursor.normalizedCursor(),
    )

fun KeywordSearchRequest.toQuery(): KeywordSearchQuery =
    KeywordSearchQuery(
        query = requireText(q, "q"),
        projectId = projectId.toOptionalId(::ProjectId),
        iterationId = iterationId.toOptionalId(::IterationId),
        artifactType = parseOptionalEnum<ArtifactType>(artifactType, "artifactType"),
        sourcePath = sourcePath?.trim()?.takeIf(String::isNotEmpty),
        taskId = taskId.toOptionalId(::TaskId),
        runId = runId.toOptionalId(::RunId),
        metadataFilters = metadataFilters.validateMetadataFilters("metadataFilters"),
        limit = limit ?: DEFAULT_SEARCH_LIMIT,
        cursor = cursor.normalizedCursor(),
    )

fun VectorSearchRequest.toQuery(): VectorSearchQuery {
    val embeddingValues = requireNotNull(embedding) { "embedding is required" }
    require(embeddingValues.isNotEmpty()) { "embedding must not be empty" }
    require(embeddingValues.all { it.isFinite() }) { "embedding values must be finite" }
    val dimension = requireNotNull(embeddingDimension) { "embeddingDimension is required" }
    require(dimension > 0) { "embeddingDimension must be positive" }
    require(embeddingValues.size == dimension) { "embeddingDimension must match embedding size" }
    return VectorSearchQuery(
        embedding = Embedding(embeddingValues),
        embeddingModel = requireText(embeddingModel, "embeddingModel"),
        embeddingDimension = dimension,
        embeddingVersion = requireText(embeddingVersion, "embeddingVersion"),
        distanceMetric = parseOptionalEnum<DistanceMetric>(distanceMetric, "distanceMetric") ?: DistanceMetric.COSINE,
        projectId = projectId.toOptionalId(::ProjectId),
        iterationId = iterationId.toOptionalId(::IterationId),
        artifactType = parseOptionalEnum<ArtifactType>(artifactType, "artifactType"),
        sourcePath = sourcePath?.trim()?.takeIf(String::isNotEmpty),
        taskId = taskId.toOptionalId(::TaskId),
        runId = runId.toOptionalId(::RunId),
        metadataFilters = metadataFilters.validateMetadataFilters("metadataFilters"),
        limit = limit ?: DEFAULT_SEARCH_LIMIT,
        cursor = cursor.normalizedCursor(),
    )
}

fun HybridSearchRequest.toQuery(): HybridSearchQuery {
    val embeddingValues = requireNotNull(embedding) { "embedding is required" }
    require(embeddingValues.isNotEmpty()) { "embedding must not be empty" }
    require(embeddingValues.all { it.isFinite() }) { "embedding values must be finite" }
    val dimension = requireNotNull(embeddingDimension) { "embeddingDimension is required" }
    require(dimension > 0) { "embeddingDimension must be positive" }
    require(embeddingValues.size == dimension) { "embeddingDimension must match embedding size" }
    val resolvedLimit = limit ?: DEFAULT_SEARCH_LIMIT
    val resolvedCandidateLimit = candidateLimit ?: maxOf(DEFAULT_HYBRID_CANDIDATE_LIMIT, resolvedLimit * 4)
    return HybridSearchQuery(
        query = requireText(q, "q"),
        embedding = Embedding(embeddingValues),
        embeddingModel = requireText(embeddingModel, "embeddingModel"),
        embeddingDimension = dimension,
        embeddingVersion = requireText(embeddingVersion, "embeddingVersion"),
        distanceMetric = parseOptionalEnum<DistanceMetric>(distanceMetric, "distanceMetric") ?: DistanceMetric.COSINE,
        projectId = projectId.toOptionalId(::ProjectId),
        iterationId = iterationId.toOptionalId(::IterationId),
        artifactType = parseOptionalEnum<ArtifactType>(artifactType, "artifactType"),
        sourcePath = sourcePath?.trim()?.takeIf(String::isNotEmpty),
        taskId = taskId.toOptionalId(::TaskId),
        runId = runId.toOptionalId(::RunId),
        metadataFilters = metadataFilters.validateMetadataFilters("metadataFilters"),
        rrfK = rrfK ?: DEFAULT_RRF_K,
        candidateLimit = resolvedCandidateLimit,
        limit = resolvedLimit,
        cursor = cursor.normalizedCursor(),
    )
}

fun <T, R> PagedResult<T>.toRestPage(mapItem: (T) -> R): PagedResponse<R> =
    PagedResponse(
        items = items.map(mapItem),
        nextCursor = nextCursor,
    )

fun ArtifactSummary.toLookupResponse(): ArtifactLookupResponse {
    val sourceIds = sourceIdsFrom(metadata)
    val snapshotVersion = metadata["snapshotVersion"]?.toIntOrNull()
    return ArtifactLookupResponse(
        artifactType = artifactType.name,
        artifactId = artifactId,
        projectId = projectId.value,
        iterationId = iterationId?.value,
        taskId = taskId?.value,
        runId = runId?.value,
        sourcePath = sourcePath,
        title = title,
        contentHash = contentHash?.value,
        snapshotVersion = snapshotVersion,
        lineage = ArtifactLineageResponse(
            projectId = projectId.value,
            iterationId = iterationId?.value,
            sourcePath = sourcePath,
            contentHash = contentHash?.value,
            snapshotVersion = snapshotVersion,
            taskId = taskId?.value,
            runId = runId?.value,
        ),
        sourceIds = sourceIds,
        sourceReference = sourceReference?.toRestResponse(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadata = metadata,
    )
}

fun KeywordSearchMatch.toResponse(): KeywordSearchResponse {
    val sourceIds = sourceIdsFrom(metadata)
    val lineage = SearchLineageResponse(
        projectId = projectId.value,
        iterationId = iterationId?.value,
        documentId = documentId?.value,
        chunkId = chunkId?.value,
        sourcePath = sourcePath,
        chunkIndex = chunkIndex,
    )
    val sourceReferenceResponse = sourceReference?.toRestResponse()
    return KeywordSearchResponse(
        chunkId = chunkId?.value,
        documentId = documentId?.value,
        projectId = projectId.value,
        iterationId = iterationId?.value,
        artifactType = artifactType.name,
        sourcePath = sourcePath,
        chunkIndex = chunkIndex,
        content = content,
        score = score,
        matchReason = matchReason,
        lineage = lineage,
        sourceIds = sourceIds,
        sourceReference = sourceReferenceResponse,
        citation = SearchCitationResponse(
            lineage = lineage,
            sourceIds = sourceIds,
            sourceReference = sourceReferenceResponse,
        ),
        metadata = metadata,
    )
}

fun VectorSearchMatch.toResponse(): VectorSearchResponse {
    val sourceIds = sourceIdsFrom(metadata)
    val lineage = SearchLineageResponse(
        projectId = projectId.value,
        iterationId = iterationId?.value,
        documentId = documentId?.value,
        chunkId = chunkId?.value,
        sourcePath = sourcePath,
        chunkIndex = chunkIndex,
    )
    val sourceReferenceResponse = sourceReference?.toRestResponse()
    return VectorSearchResponse(
        chunkId = chunkId?.value,
        documentId = documentId?.value,
        projectId = projectId.value,
        iterationId = iterationId?.value,
        artifactType = artifactType.name,
        sourcePath = sourcePath,
        chunkIndex = chunkIndex,
        content = content,
        score = score,
        distanceMetric = distanceMetric.name,
        embeddingModel = embeddingModel,
        embeddingVersion = embeddingVersion,
        lineage = lineage,
        sourceIds = sourceIds,
        sourceReference = sourceReferenceResponse,
        citation = SearchCitationResponse(
            lineage = lineage,
            sourceIds = sourceIds,
            sourceReference = sourceReferenceResponse,
        ),
        metadata = metadata,
    )
}

fun HybridSearchMatch.toResponse(): HybridSearchResponse {
    val sourceIds = sourceIdsFrom(metadata)
    val lineage = SearchLineageResponse(
        projectId = projectId.value,
        iterationId = iterationId?.value,
        documentId = documentId?.value,
        chunkId = chunkId?.value,
        sourcePath = sourcePath,
        chunkIndex = chunkIndex,
    )
    val sourceReferenceResponse = sourceReference?.toRestResponse()
    return HybridSearchResponse(
        chunkId = chunkId?.value,
        documentId = documentId?.value,
        projectId = projectId.value,
        iterationId = iterationId?.value,
        artifactType = artifactType.name,
        sourcePath = sourcePath,
        chunkIndex = chunkIndex,
        content = content,
        score = score,
        matchReason = matchReason,
        keyword = keyword?.toResponse(),
        vector = vector?.toResponse(),
        lineage = lineage,
        sourceIds = sourceIds,
        sourceReference = sourceReferenceResponse,
        citation = SearchCitationResponse(
            lineage = lineage,
            sourceIds = sourceIds,
            sourceReference = sourceReferenceResponse,
        ),
        metadata = metadata,
    )
}

private fun HybridSearchArm.toResponse(): HybridSearchArmResponse =
    HybridSearchArmResponse(
        rank = rank,
        score = score,
    )

private fun ArtifactLookupRequest.toSourceReferenceFilter(): SourceReference? {
    val canonicalServerId = sourceReferenceCanonicalServerId?.trim()?.takeIf(String::isNotEmpty)
    val uri = sourceReferenceUri?.trim()?.takeIf(String::isNotEmpty)
    require((canonicalServerId == null && uri == null) || (canonicalServerId != null && uri != null)) {
        "sourceReferenceCanonicalServerId and sourceReferenceUri must be supplied together"
    }
    return if (canonicalServerId == null || uri == null) {
        null
    } else {
        SourceReference(canonicalServerId = CanonicalServerId(canonicalServerId), uri = uri)
    }
}

private fun <T> String?.toOptionalId(factory: (String) -> T): T? =
    this?.trim()?.takeIf(String::isNotEmpty)?.let(factory)

private fun String?.normalizedCursor(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

private fun Map<String, String>.validateMetadataFilters(field: String): Map<String, String> {
    require(keys.all { it.isNotBlank() }) { "$field keys must not be blank" }
    require(values.all { it.isNotBlank() }) { "$field values must not be blank" }
    return mapKeys { it.key.trim() }.mapValues { it.value.trim() }
}

private inline fun <reified T : Enum<T>> parseOptionalEnum(value: String?, field: String): T? {
    val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return try {
        enumValueOf<T>(normalized.uppercase())
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("$field has invalid value")
    }
}

private fun SourceReference.toRestResponse(): SourceReferenceResponse =
    SourceReferenceResponse(
        canonicalServerId = canonicalServerId.value,
        uri = uri,
        path = path,
        startLine = startLine,
        endLine = endLine,
        fragment = fragment,
    )

private fun sourceIdsFrom(metadata: Map<String, String>): SourceIdsResponse =
    SourceIdsResponse(
        sourceProjectId = metadata["sourceProjectId"],
        sourceIterationId = metadata["sourceIterationId"],
        sourceDocumentId = metadata["sourceDocumentId"],
        sourceTaskGraphId = metadata["sourceTaskGraphId"],
        sourceTaskId = metadata["sourceTaskId"],
        sourceRunId = metadata["sourceRunId"],
        sourceChunkId = metadata["sourceChunkId"],
    )

data class ArtifactGraphNodeResponse(
    val nodeId: String,
    val projectId: String,
    val iterationId: String? = null,
    val nodeKind: String,
    val naturalKey: String,
    val label: String,
    val content: String? = null,
    val documentId: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class ArtifactTraceNodeResponse(val node: ArtifactGraphNodeResponse, val depth: Int)
data class ArtifactGraphEdgeResponse(val edgeId: String, val projectId: String, val fromNodeId: String, val toNodeId: String, val edgeType: String, val sourceReference: String? = null, val metadata: Map<String, String> = emptyMap())
data class ArtifactTraceResponse(val root: ArtifactGraphNodeResponse, val nodes: List<ArtifactTraceNodeResponse>, val edges: List<ArtifactGraphEdgeResponse>, val truncated: Boolean)

fun graphNodeSearchQuery(projectId: String?, iterationId: String?, nodeKind: String?, query: String?, limit: Int?): GraphNodeSearchQuery = GraphNodeSearchQuery(
    projectId = ProjectId(requireText(projectId, "projectId")),
    iterationId = iterationId.toOptionalId(::IterationId),
    nodeKind = parseOptionalEnum<com.github.silbaram.plan2agent.memory.domain.ArtifactNodeKind>(nodeKind, "nodeKind"),
    query = query?.trim()?.takeIf(String::isNotEmpty),
    limit = limit ?: DEFAULT_SEARCH_LIMIT,
)

fun graphTraceQuery(projectId: String?, naturalKey: String?, iterationId: String?, direction: String?, maxDepth: Int?): GraphTraceQuery {
    val resolvedMaxDepth = maxDepth ?: 10
    require(resolvedMaxDepth <= 30) { "maxDepth must be less than or equal to 30" }
    return GraphTraceQuery(
        projectId = ProjectId(requireText(projectId, "projectId")),
        naturalKey = requireText(naturalKey, "naturalKey"),
        iterationId = iterationId.toOptionalId(::IterationId),
        direction = parseOptionalEnum<GraphTraceDirection>(direction, "direction") ?: GraphTraceDirection.BOTH,
        maxDepth = resolvedMaxDepth,
    )
}

fun com.github.silbaram.plan2agent.memory.domain.ArtifactNode.toResponse(): ArtifactGraphNodeResponse = ArtifactGraphNodeResponse(id.value, projectId.value, iterationId?.value, kind.name, naturalKey, label, content, documentId?.value, taskId?.value, runId?.value, metadata)
fun com.github.silbaram.plan2agent.memory.domain.ArtifactEdge.toResponse(): ArtifactGraphEdgeResponse = ArtifactGraphEdgeResponse(id.value, projectId.value, fromNodeId.value, toNodeId.value, type.name, sourceReference, metadata)
fun com.github.silbaram.plan2agent.memory.domain.ArtifactTrace.toResponse(): ArtifactTraceResponse = ArtifactTraceResponse(root.toResponse(), nodes.map { ArtifactTraceNodeResponse(it.node.toResponse(), it.depth) }, edges.map { it.toResponse() }, truncated)
