package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.usecase.DEFAULT_DASHBOARD_PAGE_LIMIT
import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactDetailQuery
import com.github.silbaram.plan2agent.memory.application.usecase.FindIterationSummariesQuery
import com.github.silbaram.plan2agent.memory.application.usecase.FindProjectSummariesQuery
import com.github.silbaram.plan2agent.memory.application.usecase.MAX_DASHBOARD_PAGE_LIMIT
import com.github.silbaram.plan2agent.memory.domain.ArtifactRef
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactDetail
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactLineage
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactSource
import com.github.silbaram.plan2agent.memory.domain.readmodel.DashboardArtifactPolicy
import com.github.silbaram.plan2agent.memory.domain.readmodel.IterationSummary
import com.github.silbaram.plan2agent.memory.domain.readmodel.ProjectSummary
import java.time.Instant

private const val MAX_DASHBOARD_CURSOR_LENGTH = 2_048
private val CANONICAL_UUID_PATTERN = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
)
private val CURSOR_PATTERN = Regex("^[a-zA-Z0-9_-]+={0,2}$")

data class DashboardProjectListRequest(
    val limit: Int? = null,
    val cursor: String? = null,
)

data class DashboardIterationListRequest(
    val projectId: String,
    val limit: Int? = null,
    val cursor: String? = null,
)

data class DashboardArtifactDetailRequest(
    val artifactType: String,
    val artifactId: String,
)

data class DashboardProjectSummaryResponse(
    val projectId: String,
    val sourceProjectId: String,
    val name: String,
    val canonicalServerId: String,
    val rootPath: String,
    val sourceReference: DashboardSourceReferenceResponse? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class DashboardIterationSummaryResponse(
    val iterationId: String,
    val projectId: String,
    val sourceIterationId: String,
    val label: String,
    val status: String,
    val sourceReference: DashboardSourceReferenceResponse? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class DashboardArtifactDetailResponse(
    val artifactType: String,
    val artifactId: String,
    val projectId: String,
    val iterationId: String? = null,
    val title: String,
    val source: DashboardArtifactSourceResponse,
    val lineage: DashboardArtifactLineageResponse,
    val mediaType: String,
    val rawContent: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class DashboardArtifactSourceResponse(
    val sourceProjectId: String? = null,
    val sourceIterationId: String? = null,
    val sourceDocumentId: String? = null,
    val sourceTaskGraphId: String? = null,
    val sourceTaskId: String? = null,
    val sourceRunId: String? = null,
    val sourcePath: String? = null,
    val sourceReference: DashboardSourceReferenceResponse? = null,
)

data class DashboardArtifactLineageResponse(
    val projectId: String,
    val iterationId: String? = null,
    val documentId: String? = null,
    val taskGraphId: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val contentHash: String? = null,
    val snapshotVersion: Int? = null,
    val artifactRefs: List<DashboardArtifactReferenceResponse> = emptyList(),
)

data class DashboardArtifactReferenceResponse(
    val artifactType: String,
    val artifactId: String,
    val sourcePath: String? = null,
)

data class DashboardSourceReferenceResponse(
    val canonicalServerId: String,
    val uri: String,
    val path: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val fragment: String? = null,
)

fun DashboardProjectListRequest.toQuery(): FindProjectSummariesQuery =
    FindProjectSummariesQuery(
        limit = dashboardPageLimit(limit),
        cursor = cursor.toDashboardCursor(),
    )

fun DashboardIterationListRequest.toQuery(): FindIterationSummariesQuery =
    FindIterationSummariesQuery(
        projectId = projectId.toProjectId(),
        limit = dashboardPageLimit(limit),
        cursor = cursor.toDashboardCursor(),
    )

fun DashboardArtifactDetailRequest.toQuery(): FindArtifactDetailQuery =
    FindArtifactDetailQuery(
        artifactType = artifactType.toDashboardArtifactType(),
        artifactId = artifactId.toCanonicalId("artifactId"),
    )

fun ProjectSummary.toDashboardResponse(): DashboardProjectSummaryResponse =
    DashboardProjectSummaryResponse(
        projectId = projectId.value,
        sourceProjectId = sourceProjectId.value,
        name = name,
        canonicalServerId = canonicalServerId.value,
        rootPath = rootPath,
        sourceReference = sourceReference?.toDashboardResponse(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadata = metadata,
    )

fun IterationSummary.toDashboardResponse(): DashboardIterationSummaryResponse =
    DashboardIterationSummaryResponse(
        iterationId = iterationId.value,
        projectId = projectId.value,
        sourceIterationId = sourceIterationId.value,
        label = label,
        status = status.toDashboardResponse(),
        sourceReference = sourceReference?.toDashboardResponse(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadata = metadata,
    )

fun ArtifactDetail.toDashboardResponse(): DashboardArtifactDetailResponse =
    DashboardArtifactDetailResponse(
        artifactType = artifactType.name,
        artifactId = artifactId,
        projectId = projectId.value,
        iterationId = iterationId?.value,
        title = title,
        source = source.toDashboardResponse(),
        lineage = lineage.toDashboardResponse(),
        mediaType = mediaType,
        rawContent = rawContent,
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadata = metadata,
    )

private fun dashboardPageLimit(limit: Int?): Int {
    val resolvedLimit = limit ?: DEFAULT_DASHBOARD_PAGE_LIMIT
    require(resolvedLimit in 1..MAX_DASHBOARD_PAGE_LIMIT) {
        "limit must be between 1 and $MAX_DASHBOARD_PAGE_LIMIT"
    }
    return resolvedLimit
}

private fun String?.toDashboardCursor(): String? {
    val normalized = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    require(normalized.length <= MAX_DASHBOARD_CURSOR_LENGTH && CURSOR_PATTERN.matches(normalized)) {
        "cursor has invalid format"
    }
    return normalized
}

private fun String.toProjectId(): ProjectId =
    ProjectId(toCanonicalId("projectId"))

private fun String.toCanonicalId(field: String): String {
    val normalized = trim()
    require(CANONICAL_UUID_PATTERN.matches(normalized)) { "$field has invalid value" }
    return normalized
}

private fun String.toDashboardArtifactType(): ArtifactType {
    val normalized = trim().uppercase()
    val artifactType = ArtifactType.entries.firstOrNull { it.name == normalized }
        ?: throw IllegalArgumentException("artifactType has invalid value")
    require(DashboardArtifactPolicy.isTreeAndDetailArtifact(artifactType)) {
        "artifactType has invalid value"
    }
    return artifactType
}

private fun ArtifactSource.toDashboardResponse(): DashboardArtifactSourceResponse =
    DashboardArtifactSourceResponse(
        sourceProjectId = sourceProjectId?.value,
        sourceIterationId = sourceIterationId?.value,
        sourceDocumentId = sourceDocumentId?.value,
        sourceTaskGraphId = sourceTaskGraphId?.value,
        sourceTaskId = sourceTaskId?.value,
        sourceRunId = sourceRunId?.value,
        sourcePath = sourcePath,
        sourceReference = sourceReference?.toDashboardResponse(),
    )

private fun ArtifactLineage.toDashboardResponse(): DashboardArtifactLineageResponse =
    DashboardArtifactLineageResponse(
        projectId = projectId.value,
        iterationId = iterationId?.value,
        documentId = documentId?.value,
        taskGraphId = taskGraphId?.value,
        taskId = taskId?.value,
        runId = runId?.value,
        contentHash = contentHash?.value,
        snapshotVersion = snapshotVersion,
        artifactRefs = artifactRefs.map(ArtifactRef::toDashboardResponse),
    )

private fun ArtifactRef.toDashboardResponse(): DashboardArtifactReferenceResponse =
    DashboardArtifactReferenceResponse(
        artifactType = artifactType.name,
        artifactId = artifactId,
        sourcePath = sourcePath,
    )

private fun SourceReference.toDashboardResponse(): DashboardSourceReferenceResponse =
    DashboardSourceReferenceResponse(
        canonicalServerId = canonicalServerId.value,
        uri = uri,
        path = path,
        startLine = startLine,
        endLine = endLine,
        fragment = fragment,
    )

private fun IterationStatus.toDashboardResponse(): String = name
