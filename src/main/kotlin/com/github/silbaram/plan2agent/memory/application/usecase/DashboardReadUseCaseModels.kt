package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactIdentity

const val DEFAULT_DASHBOARD_PAGE_LIMIT = 50
const val MAX_DASHBOARD_PAGE_LIMIT = 200

/** Public request for the dashboard project list. Its cursor is bound to this endpoint. */
data class FindProjectSummariesQuery(
    val limit: Int = DEFAULT_DASHBOARD_PAGE_LIMIT,
    val cursor: String? = null,
) {
    init {
        require(limit > 0) { "Project summary limit must be positive" }
        require(cursor == null || cursor.isNotBlank()) { "Project summary cursor must not be blank" }
    }
}

/** Public request for one project's dashboard iteration list. */
data class FindIterationSummariesQuery(
    val projectId: ProjectId,
    val limit: Int = DEFAULT_DASHBOARD_PAGE_LIMIT,
    val cursor: String? = null,
) {
    init {
        require(limit > 0) { "Iteration summary limit must be positive" }
        require(cursor == null || cursor.isNotBlank()) { "Iteration summary cursor must not be blank" }
    }
}

/** Public lookup request for one dashboard artifact. The type and id form its whole identity. */
data class FindArtifactDetailQuery(
    val artifactType: ArtifactType,
    val artifactId: String,
) {
    val identity: ArtifactIdentity
        get() = ArtifactIdentity(artifactType = artifactType, artifactId = artifactId)

    init {
        require(artifactId.isNotBlank()) { "Artifact detail artifactId must not be blank" }
    }
}

/**
 * Internal adapter request for stable keyset project pagination. The cursor is the adapter's
 * continuation key after the public endpoint binding has been checked by the use case.
 */
data class ProjectSummaryPageQuery(
    val limit: Int,
    val cursor: String? = null,
)

/** Internal adapter request for stable keyset pagination under exactly one project. */
data class IterationSummaryPageQuery(
    val projectId: ProjectId,
    val limit: Int,
    val cursor: String? = null,
)
