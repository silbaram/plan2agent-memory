package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.silbaram.plan2agent.memory.application.port.out.DashboardReadPort
import com.github.silbaram.plan2agent.memory.application.usecase.IterationSummaryPageQuery
import com.github.silbaram.plan2agent.memory.application.usecase.MAX_DASHBOARD_PAGE_LIMIT
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.ProjectSummaryPageQuery
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactDetail
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactIdentity
import com.github.silbaram.plan2agent.memory.domain.readmodel.IterationSummary
import com.github.silbaram.plan2agent.memory.domain.readmodel.ProjectSummary
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * PostgreSQL read adapter for the dashboard hierarchy.
 *
 * The cursor records the final sort values instead of an offset, so inserting earlier rows does
 * not cause a subsequent page to repeat or skip existing rows. Project and iteration IDs are the
 * deterministic tie breakers for otherwise equal creation timestamps.
 */
@Repository
class PostgresDashboardReadAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : DashboardReadPort {
    private val json = PostgresJsonSupport(objectMapper)
    private val cursorCodec = DashboardHierarchyCursorCodec(objectMapper)

    override fun findProjectSummaries(query: ProjectSummaryPageQuery): PagedResult<ProjectSummary> =
        metrics.recordSearch("dashboard.project.page") {
            validateLimit(query.limit)
            val cursor = query.cursor?.let(cursorCodec::decodeProject)
            val params = MapSqlParameterSource().addValue("limitPlusOne", query.limit + 1)
            val keysetCondition = cursor?.let {
                params
                    .addValue("cursorCreatedAt", Timestamp.from(it.createdAt))
                    .addValue("cursorProjectId", UUID.fromString(it.projectId))
                """
                WHERE created_at < :cursorCreatedAt
                   OR (created_at = :cursorCreatedAt AND project_id < :cursorProjectId)
                """.trimIndent()
            }.orEmpty()
            val rows = jdbc.query(
                """
                SELECT *
                FROM projects
                $keysetCondition
                ORDER BY created_at DESC, project_id DESC
                LIMIT :limitPlusOne
                """.trimIndent(),
                params,
                projectSummaryRowMapper(json),
            )
            rows.toProjectPage(query.limit, cursorCodec)
        }

    override fun findIterationSummaries(query: IterationSummaryPageQuery): PagedResult<IterationSummary> =
        metrics.recordSearch("dashboard.iteration.page") {
            validateLimit(query.limit)
            val cursor = query.cursor?.let(cursorCodec::decodeIteration)
            require(cursor == null || cursor.projectId == query.projectId.value) {
                "Dashboard iteration cursor does not match project"
            }
            val params = MapSqlParameterSource()
                .addValue("projectId", UUID.fromString(query.projectId.value))
                .addValue("limitPlusOne", query.limit + 1)
            val keysetCondition = cursor?.let {
                params
                    .addValue("cursorCreatedAt", Timestamp.from(it.createdAt))
                    .addValue("cursorIterationId", UUID.fromString(it.iterationId))
                """
                  AND (
                      created_at < :cursorCreatedAt
                      OR (created_at = :cursorCreatedAt AND iteration_id < :cursorIterationId)
                  )
                """.trimIndent()
            }.orEmpty()
            val rows = jdbc.query(
                """
                SELECT *
                FROM iterations
                WHERE project_id = :projectId
                $keysetCondition
                ORDER BY created_at DESC, iteration_id DESC
                LIMIT :limitPlusOne
                """.trimIndent(),
                params,
                iterationSummaryRowMapper(json),
            )
            rows.toIterationPage(query.limit, query.projectId, cursorCodec)
        }

    /** Artifact raw-detail mapping is added in task-004. */
    override fun findArtifactDetail(identity: ArtifactIdentity): ArtifactDetail? =
        throw UnsupportedOperationException("Dashboard artifact detail reads are not implemented")

    private fun validateLimit(limit: Int) {
        require(limit in 1..MAX_DASHBOARD_PAGE_LIMIT) {
            "Dashboard page limit must be between 1 and $MAX_DASHBOARD_PAGE_LIMIT"
        }
    }
}

private data class ProjectSummaryRow(
    val value: ProjectSummary,
    val cursor: ProjectSummaryCursor,
)

private data class IterationSummaryRow(
    val value: IterationSummary,
    val cursor: IterationSummaryCursor,
)

private fun projectSummaryRowMapper(json: PostgresJsonSupport): RowMapper<ProjectSummaryRow> =
    RowMapper { rs, _ ->
        val metadata = json.metadataFromJson(rs.getString("metadata"))
        val projectId = rs.getString("project_id")
        ProjectSummaryRow(
            value = ProjectSummary(
                projectId = ProjectId(projectId),
                sourceProjectId = SourceProjectId(rs.getString("source_project_id")),
                name = rs.getString("name"),
                canonicalServerId = CanonicalServerId(
                    metadata[PostgresJsonSupport.PROJECT_CANONICAL_SERVER_ID] ?: projectId,
                ),
                rootPath = rs.getString("root_path"),
                sourceReference = json.sourceReferenceFrom(metadata),
                createdAt = rs.instant("created_at"),
                updatedAt = rs.nullableInstant("updated_at"),
                metadata = json.withoutReservedMetadata(metadata),
            ),
            cursor = ProjectSummaryCursor(
                createdAt = rs.instant("created_at"),
                projectId = projectId,
            ),
        )
    }

private fun iterationSummaryRowMapper(json: PostgresJsonSupport): RowMapper<IterationSummaryRow> =
    RowMapper { rs, _ ->
        val metadata = json.metadataFromJson(rs.getString("metadata"))
        val projectId = rs.getString("project_id")
        val iterationId = rs.getString("iteration_id")
        IterationSummaryRow(
            value = IterationSummary(
                iterationId = IterationId(iterationId),
                projectId = ProjectId(projectId),
                sourceIterationId = SourceIterationId(rs.getString("source_iteration_id")),
                label = rs.getString("label"),
                status = IterationStatus.valueOf(rs.getString("status")),
                sourceReference = json.sourceReferenceFrom(metadata),
                createdAt = rs.instant("created_at"),
                updatedAt = rs.nullableInstant("updated_at"),
                metadata = json.withoutReservedMetadata(metadata),
            ),
            cursor = IterationSummaryCursor(
                projectId = projectId,
                createdAt = rs.instant("created_at"),
                iterationId = iterationId,
            ),
        )
    }

private fun List<ProjectSummaryRow>.toProjectPage(
    limit: Int,
    cursorCodec: DashboardHierarchyCursorCodec,
): PagedResult<ProjectSummary> {
    val pageRows = take(limit)
    return PagedResult(
        items = pageRows.map(ProjectSummaryRow::value),
        nextCursor = if (size > limit) cursorCodec.encode(requireNotNull(pageRows.lastOrNull()).cursor) else null,
    )
}

private fun List<IterationSummaryRow>.toIterationPage(
    limit: Int,
    projectId: ProjectId,
    cursorCodec: DashboardHierarchyCursorCodec,
): PagedResult<IterationSummary> {
    val pageRows = take(limit)
    return PagedResult(
        items = pageRows.map(IterationSummaryRow::value),
        nextCursor = if (size > limit) {
            cursorCodec.encode(requireNotNull(pageRows.lastOrNull()).cursor.copy(projectId = projectId.value))
        } else {
            null
        },
    )
}

private data class ProjectSummaryCursor(
    val kind: String = PROJECT_SUMMARY_CURSOR_KIND,
    val createdAt: Instant,
    val projectId: String,
)

private data class IterationSummaryCursor(
    val kind: String = ITERATION_SUMMARY_CURSOR_KIND,
    val projectId: String,
    val createdAt: Instant,
    val iterationId: String,
)

private class DashboardHierarchyCursorCodec(
    private val objectMapper: ObjectMapper,
) {
    fun encode(cursor: ProjectSummaryCursor): String = encodeCursor(cursor)

    fun encode(cursor: IterationSummaryCursor): String = encodeCursor(cursor)

    fun decodeProject(value: String): ProjectSummaryCursor =
        decodeCursor(value, ProjectSummaryCursor::class.java).also {
            require(it.kind == PROJECT_SUMMARY_CURSOR_KIND) {
                "Dashboard project cursor does not belong to project summaries"
            }
            validateCursorIds(it.projectId)
        }

    fun decodeIteration(value: String): IterationSummaryCursor =
        decodeCursor(value, IterationSummaryCursor::class.java).also {
            require(it.kind == ITERATION_SUMMARY_CURSOR_KIND) {
                "Dashboard iteration cursor does not belong to iteration summaries"
            }
            validateCursorIds(it.projectId, it.iterationId)
        }

    private fun encodeCursor(cursor: Any): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(cursor))

    private fun <T> decodeCursor(value: String, type: Class<T>): T =
        try {
            val decoded = Base64.getUrlDecoder().decode(value)
            objectMapper.readValue(decoded, type)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Dashboard cursor is invalid", failure)
        }

    private fun validateCursorIds(vararg ids: String) {
        ids.forEach {
            try {
                UUID.fromString(it)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("Dashboard cursor is invalid", failure)
            }
        }
    }
}

private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

private fun ResultSet.nullableInstant(column: String): Instant? = getTimestamp(column)?.toInstant()

private const val PROJECT_SUMMARY_CURSOR_KIND = "dashboard-project-summary.v1"
private const val ITERATION_SUMMARY_CURSOR_KIND = "dashboard-iteration-summary.v1"
