package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.`in`.FindArtifactDetailUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.FindIterationSummariesUseCase
import com.github.silbaram.plan2agent.memory.application.port.`in`.FindProjectSummariesUseCase
import com.github.silbaram.plan2agent.memory.application.port.out.DashboardReadPort
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactDetail
import com.github.silbaram.plan2agent.memory.domain.readmodel.DashboardArtifactPolicy
import com.github.silbaram.plan2agent.memory.domain.readmodel.IterationSummary
import com.github.silbaram.plan2agent.memory.domain.readmodel.ProjectSummary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Application boundary for dashboard-specific reads.
 *
 * The service remains directly constructible so the read contract can be independently tested.
 */
@Service
class DashboardReadUseCaseService(
    private val dashboardReadPort: DashboardReadPort,
) : FindProjectSummariesUseCase,
    FindIterationSummariesUseCase,
    FindArtifactDetailUseCase {

    @Transactional(readOnly = true)
    override fun findProjectSummaries(query: FindProjectSummariesQuery): PagedResult<ProjectSummary> {
        validatePageLimit(query.limit, "Project summary")
        val requestFingerprint = DashboardReadCursor.projectSummariesFingerprint()
        val continuationKey = query.cursor?.let { SearchRequestCursor.decode(it, requestFingerprint) }
        return dashboardReadPort
            .findProjectSummaries(ProjectSummaryPageQuery(limit = query.limit, cursor = continuationKey))
            .bindNextCursor(requestFingerprint)
    }

    @Transactional(readOnly = true)
    override fun findIterationSummaries(query: FindIterationSummariesQuery): PagedResult<IterationSummary> {
        validatePageLimit(query.limit, "Iteration summary")
        val requestFingerprint = DashboardReadCursor.iterationSummariesFingerprint(query.projectId)
        val continuationKey = query.cursor?.let { SearchRequestCursor.decode(it, requestFingerprint) }
        return dashboardReadPort
            .findIterationSummaries(
                IterationSummaryPageQuery(
                    projectId = query.projectId,
                    limit = query.limit,
                    cursor = continuationKey,
                ),
            )
            .bindNextCursor(requestFingerprint)
    }

    @Transactional(readOnly = true)
    override fun findArtifactDetail(query: FindArtifactDetailQuery): ArtifactDetail {
        require(DashboardArtifactPolicy.isTreeAndDetailArtifact(query.artifactType)) {
            "Artifact type ${query.artifactType.name} is not supported by dashboard detail"
        }
        return dashboardReadPort.findArtifactDetail(query.identity)
            ?: throw NoSuchElementException(
                "Artifact ${query.artifactType.name}/${query.artifactId} was not found",
            )
    }

    private fun validatePageLimit(limit: Int, label: String) {
        require(limit in 1..MAX_DASHBOARD_PAGE_LIMIT) {
            "$label limit must be between 1 and $MAX_DASHBOARD_PAGE_LIMIT"
        }
    }
}

private fun <T> PagedResult<T>.bindNextCursor(requestFingerprint: String): PagedResult<T> =
    copy(nextCursor = nextCursor?.let { SearchRequestCursor.encode(requestFingerprint, it) })

private object DashboardReadCursor {
    private const val PROJECT_SUMMARIES_ENDPOINT = "dashboard-project-summaries"
    private const val ITERATION_SUMMARIES_ENDPOINT = "dashboard-iteration-summaries"

    fun projectSummariesFingerprint(): String = fingerprint(PROJECT_SUMMARIES_ENDPOINT)

    fun iterationSummariesFingerprint(projectId: ProjectId): String =
        fingerprint(ITERATION_SUMMARIES_ENDPOINT, projectId.value)

    private fun fingerprint(endpoint: String, projectId: String? = null): String {
        val canonicalValue = listOf("dashboard-read-cursor.v1", endpoint, projectId.orEmpty()).joinToString("|")
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(canonicalValue.toByteArray(StandardCharsets.UTF_8)),
        )
    }
}
