package com.github.silbaram.plan2agent.memory.domain.readmodel

import com.github.silbaram.plan2agent.memory.domain.ArtifactRef
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.SourceRunId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskId
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import java.time.Instant

/**
 * Dashboard projection of a stored project. It deliberately contains only domain values so
 * persistence adapters and HTTP responses can evolve independently.
 */
data class ProjectSummary(
    val projectId: ProjectId,
    val sourceProjectId: SourceProjectId,
    val name: String,
    val canonicalServerId: CanonicalServerId,
    val rootPath: String,
    val sourceReference: SourceReference? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "ProjectSummary name must not be blank" }
        require(rootPath.isNotBlank()) { "ProjectSummary rootPath must not be blank" }
    }
}

/** Dashboard projection of a stored iteration within a project. */
data class IterationSummary(
    val iterationId: IterationId,
    val projectId: ProjectId,
    val sourceIterationId: SourceIterationId,
    val label: String,
    val status: IterationStatus,
    val sourceReference: SourceReference? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(label.isNotBlank()) { "IterationSummary label must not be blank" }
    }
}

/**
 * The lookup key for dashboard artifacts. Artifact IDs only need to be unique within their
 * artifact type, so both values are required for a detail lookup.
 */
data class ArtifactIdentity(
    val artifactType: ArtifactType,
    val artifactId: String,
) {
    init {
        require(artifactId.isNotBlank()) { "ArtifactIdentity artifactId must not be blank" }
    }
}

/**
 * Original source identifiers and location retained with a dashboard artifact. Fields are
 * optional because a stored artifact type contributes only the identifiers it owns.
 */
data class ArtifactSource(
    val sourceProjectId: SourceProjectId? = null,
    val sourceIterationId: SourceIterationId? = null,
    val sourceDocumentId: SourceDocumentId? = null,
    val sourceTaskGraphId: SourceTaskGraphId? = null,
    val sourceTaskId: SourceTaskId? = null,
    val sourceRunId: SourceRunId? = null,
    val sourcePath: String? = null,
    val sourceReference: SourceReference? = null,
) {
    init {
        require(sourcePath == null || sourcePath.isNotBlank()) {
            "ArtifactSource sourcePath must not be blank when supplied"
        }
    }
}

/**
 * Stored relationships needed to trace an artifact back through the P2A materialization graph.
 */
data class ArtifactLineage(
    val projectId: ProjectId,
    val iterationId: IterationId? = null,
    val documentId: DocumentId? = null,
    val taskGraphId: TaskGraphId? = null,
    val taskId: TaskId? = null,
    val runId: RunId? = null,
    val contentHash: ContentHash? = null,
    val snapshotVersion: Int? = null,
    val artifactRefs: List<ArtifactRef> = emptyList(),
) {
    init {
        require(snapshotVersion == null || snapshotVersion > 0) {
            "ArtifactLineage snapshotVersion must be positive when supplied"
        }
    }
}

/**
 * Full dashboard detail projection. The payload is intentionally the stored raw content rather
 * than a persistence entity or a transport-specific DTO.
 */
data class ArtifactDetail(
    val artifactType: ArtifactType,
    val artifactId: String,
    val projectId: ProjectId,
    val iterationId: IterationId? = null,
    val title: String,
    val source: ArtifactSource,
    val lineage: ArtifactLineage,
    val mediaType: String,
    val rawContent: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    val identity: ArtifactIdentity
        get() = ArtifactIdentity(artifactType = artifactType, artifactId = artifactId)

    init {
        require(DashboardArtifactPolicy.isTreeAndDetailArtifact(artifactType)) {
            "ArtifactDetail does not support dashboard artifact type $artifactType"
        }
        require(artifactId.isNotBlank()) { "ArtifactDetail artifactId must not be blank" }
        require(title.isNotBlank()) { "ArtifactDetail title must not be blank" }
        require(mediaType.isNotBlank()) { "ArtifactDetail mediaType must not be blank" }
        require(lineage.projectId == projectId) {
            "ArtifactDetail lineage projectId must match projectId"
        }
        require(lineage.iterationId == iterationId) {
            "ArtifactDetail lineage iterationId must match iterationId"
        }
    }
}

/** The artifact kinds intentionally exposed by the dashboard tree and raw-detail view. */
object DashboardArtifactPolicy {
    private val treeAndDetailArtifactTypes = setOf(
        ArtifactType.DOCUMENT_SNAPSHOT,
        ArtifactType.TASK_GRAPH,
        ArtifactType.TASK,
        ArtifactType.RUN_RECORD,
        ArtifactType.PROPOSAL,
    )

    fun isTreeAndDetailArtifact(artifactType: ArtifactType): Boolean =
        artifactType in treeAndDetailArtifactTypes
}
