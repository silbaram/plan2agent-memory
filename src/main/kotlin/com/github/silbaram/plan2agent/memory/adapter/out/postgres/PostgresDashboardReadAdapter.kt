package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.silbaram.plan2agent.memory.application.port.out.DashboardReadPort
import com.github.silbaram.plan2agent.memory.application.usecase.IterationSummaryPageQuery
import com.github.silbaram.plan2agent.memory.application.usecase.MAX_DASHBOARD_PAGE_LIMIT
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.ProjectSummaryPageQuery
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
import com.github.silbaram.plan2agent.memory.domain.SourceRunId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskId
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactDetail
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactIdentity
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactLineage
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactSource
import com.github.silbaram.plan2agent.memory.domain.readmodel.DashboardArtifactPolicy
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

    override fun findArtifactDetail(identity: ArtifactIdentity): ArtifactDetail? =
        metrics.recordSearch("dashboard.artifact.detail") {
            require(DashboardArtifactPolicy.isTreeAndDetailArtifact(identity.artifactType)) {
                "Artifact type ${identity.artifactType.name} is not supported by dashboard detail"
            }
            when (identity.artifactType) {
                ArtifactType.DOCUMENT_SNAPSHOT,
                ArtifactType.PROPOSAL,
                -> findDocumentArtifactDetail(identity)

                ArtifactType.TASK_GRAPH -> findTaskGraphArtifactDetail(identity)
                ArtifactType.TASK -> findTaskArtifactDetail(identity)
                ArtifactType.RUN_RECORD -> findRunArtifactDetail(identity)

                ArtifactType.PROJECT,
                ArtifactType.ITERATION,
                ArtifactType.DOCUMENT_CHUNK,
                -> error("Unsupported dashboard artifact type ${identity.artifactType}")
            }
        }

    private fun findDocumentArtifactDetail(identity: ArtifactIdentity): ArtifactDetail? =
        jdbc.queryOne(
            """
            SELECT
                d.document_id::text AS artifact_id,
                d.project_id::text AS project_id,
                d.iteration_id::text AS iteration_id,
                d.document_id::text AS document_id,
                NULL::text AS task_graph_id,
                NULL::text AS task_id,
                NULL::text AS run_id,
                d.content_hash,
                d.snapshot_version,
                p.source_project_id,
                i.source_iteration_id,
                d.source_document_id,
                NULL::text AS source_task_graph_id,
                NULL::text AS source_task_id,
                NULL::text AS source_run_id,
                d.source_path,
                COALESCE(d.metadata ->> '${PostgresJsonSupport.DOCUMENT_TITLE}', d.source_path) AS title,
                COALESCE(
                    NULLIF(d.metadata ->> 'mediaType', ''),
                    CASE
                        WHEN lower(d.source_path) LIKE '%.md' OR lower(d.source_path) LIKE '%.markdown'
                            THEN 'text/markdown'
                        WHEN lower(d.source_path) LIKE '%.json' THEN 'application/json'
                        ELSE 'text/plain'
                    END
                ) AS media_type,
                d.content AS raw_content,
                NULL::text AS artifact_refs_json,
                d.metadata,
                d.created_at,
                d.updated_at
            FROM documents d
            JOIN projects p ON p.project_id = d.project_id
            LEFT JOIN iterations i ON i.iteration_id = d.iteration_id
            WHERE d.document_id = :artifactId
              AND d.artifact_type = :artifactType
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("artifactId", UUID.fromString(identity.artifactId))
                .addValue("artifactType", identity.artifactType.name),
            artifactDetailRowMapper(identity.artifactType, json),
        )

    private fun findTaskGraphArtifactDetail(identity: ArtifactIdentity): ArtifactDetail? =
        jdbc.queryOne(
            """
            SELECT
                tg.task_graph_id::text AS artifact_id,
                tg.project_id::text AS project_id,
                tg.iteration_id::text AS iteration_id,
                tg.document_id::text AS document_id,
                tg.task_graph_id::text AS task_graph_id,
                NULL::text AS task_id,
                NULL::text AS run_id,
                tg.graph_hash AS content_hash,
                NULL::integer AS snapshot_version,
                p.source_project_id,
                i.source_iteration_id,
                COALESCE(tg.source_document_id, d.source_document_id) AS source_document_id,
                tg.source_task_graph_id,
                NULL::text AS source_task_id,
                NULL::text AS source_run_id,
                d.source_path,
                COALESCE(tg.source_task_graph_id, tg.task_graph_id::text) AS title,
                COALESCE(NULLIF(tg.metadata ->> 'mediaType', ''), 'application/json') AS media_type,
                tg.graph_json::text AS raw_content,
                NULL::text AS artifact_refs_json,
                tg.metadata,
                tg.created_at,
                tg.updated_at
            FROM task_graphs tg
            JOIN projects p ON p.project_id = tg.project_id
            JOIN iterations i ON i.iteration_id = tg.iteration_id
            LEFT JOIN documents d ON d.document_id = tg.document_id
            WHERE tg.task_graph_id = :artifactId
            """.trimIndent(),
            MapSqlParameterSource("artifactId", UUID.fromString(identity.artifactId)),
            artifactDetailRowMapper(identity.artifactType, json),
        )

    private fun findTaskArtifactDetail(identity: ArtifactIdentity): ArtifactDetail? =
        jdbc.queryOne(
            """
            SELECT
                t.task_id::text AS artifact_id,
                t.project_id::text AS project_id,
                t.iteration_id::text AS iteration_id,
                tg.document_id::text AS document_id,
                t.task_graph_id::text AS task_graph_id,
                t.task_id::text AS task_id,
                NULL::text AS run_id,
                NULL::text AS content_hash,
                NULL::integer AS snapshot_version,
                p.source_project_id,
                i.source_iteration_id,
                COALESCE(tg.source_document_id, d.source_document_id) AS source_document_id,
                tg.source_task_graph_id,
                t.source_task_id,
                NULL::text AS source_run_id,
                d.source_path,
                t.title,
                COALESCE(NULLIF(t.metadata ->> 'mediaType', ''), 'application/json') AS media_type,
                to_jsonb(t)::text AS raw_content,
                NULL::text AS artifact_refs_json,
                t.metadata,
                t.created_at,
                t.updated_at
            FROM tasks t
            JOIN projects p ON p.project_id = t.project_id
            JOIN iterations i ON i.iteration_id = t.iteration_id
            JOIN task_graphs tg ON tg.task_graph_id = t.task_graph_id
            LEFT JOIN documents d ON d.document_id = tg.document_id
            WHERE t.task_id = :artifactId
            """.trimIndent(),
            MapSqlParameterSource("artifactId", UUID.fromString(identity.artifactId)),
            artifactDetailRowMapper(identity.artifactType, json),
        )

    private fun findRunArtifactDetail(identity: ArtifactIdentity): ArtifactDetail? =
        jdbc.queryOne(
            """
            SELECT
                r.run_id::text AS artifact_id,
                r.project_id::text AS project_id,
                r.iteration_id::text AS iteration_id,
                NULL::text AS document_id,
                t.task_graph_id::text AS task_graph_id,
                r.task_id::text AS task_id,
                r.run_id::text AS run_id,
                NULL::text AS content_hash,
                NULL::integer AS snapshot_version,
                p.source_project_id,
                i.source_iteration_id,
                NULL::text AS source_document_id,
                tg.source_task_graph_id,
                t.source_task_id,
                r.source_run_id,
                NULL::text AS source_path,
                COALESCE(r.source_run_id, r.run_id::text) AS title,
                COALESCE(NULLIF(r.metadata ->> 'mediaType', ''), 'application/json') AS media_type,
                r.run_json::text AS raw_content,
                r.artifact_refs_json::text AS artifact_refs_json,
                r.metadata,
                r.created_at,
                r.updated_at
            FROM runs r
            JOIN projects p ON p.project_id = r.project_id
            JOIN iterations i ON i.iteration_id = r.iteration_id
            JOIN tasks t ON t.task_id = r.task_id
            JOIN task_graphs tg ON tg.task_graph_id = t.task_graph_id
            WHERE r.run_id = :artifactId
            """.trimIndent(),
            MapSqlParameterSource("artifactId", UUID.fromString(identity.artifactId)),
            artifactDetailRowMapper(identity.artifactType, json),
        )

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

private fun artifactDetailRowMapper(
    artifactType: ArtifactType,
    json: PostgresJsonSupport,
): RowMapper<ArtifactDetail> =
    RowMapper { rs, _ ->
        val metadata = json.metadataFromJson(rs.getString("metadata"))
        val projectId = ProjectId(rs.getString("project_id"))
        val iterationId = rs.getString("iteration_id")?.let(::IterationId)
        ArtifactDetail(
            artifactType = artifactType,
            artifactId = rs.getString("artifact_id"),
            projectId = projectId,
            iterationId = iterationId,
            title = rs.getString("title"),
            source = ArtifactSource(
                sourceProjectId = rs.getString("source_project_id")?.let(::SourceProjectId),
                sourceIterationId = rs.getString("source_iteration_id")?.let(::SourceIterationId),
                sourceDocumentId = rs.getString("source_document_id")?.let(::SourceDocumentId),
                sourceTaskGraphId = rs.getString("source_task_graph_id")?.let(::SourceTaskGraphId),
                sourceTaskId = rs.getString("source_task_id")?.let(::SourceTaskId),
                sourceRunId = rs.getString("source_run_id")?.let(::SourceRunId),
                sourcePath = rs.getString("source_path"),
                sourceReference = json.sourceReferenceFrom(metadata),
            ),
            lineage = ArtifactLineage(
                projectId = projectId,
                iterationId = iterationId,
                documentId = rs.getString("document_id")?.let(::DocumentId),
                taskGraphId = rs.getString("task_graph_id")?.let(::TaskGraphId),
                taskId = rs.getString("task_id")?.let(::TaskId),
                runId = rs.getString("run_id")?.let(::RunId),
                contentHash = rs.getString("content_hash")?.let(::ContentHash),
                snapshotVersion = rs.nullableInt("snapshot_version"),
                artifactRefs = json.artifactRefsFromJson(rs.getString("artifact_refs_json"))
                    .map {
                        ArtifactRef(
                            artifactType = ArtifactType.valueOf(it.artifactType),
                            artifactId = it.artifactId,
                            sourcePath = it.sourcePath,
                        )
                    },
            ),
            mediaType = rs.getString("media_type"),
            rawContent = rs.getString("raw_content"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.nullableInstant("updated_at"),
            metadata = json.withoutReservedMetadata(metadata),
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

private fun ResultSet.nullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

private fun <T> NamedParameterJdbcTemplate.queryOne(
    sql: String,
    params: MapSqlParameterSource,
    mapper: RowMapper<T>,
): T? =
    try {
        queryForObject(sql, params, mapper)
    } catch (_: org.springframework.dao.EmptyResultDataAccessException) {
        null
    }

private const val PROJECT_SUMMARY_CURSOR_KIND = "dashboard-project-summary.v1"
private const val ITERATION_SUMMARY_CURSOR_KIND = "dashboard-iteration-summary.v1"
