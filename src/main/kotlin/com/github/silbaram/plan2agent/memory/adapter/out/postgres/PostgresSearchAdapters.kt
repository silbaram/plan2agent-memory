package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactQueryPort
import com.github.silbaram.plan2agent.memory.application.port.out.KeywordSearchPort
import com.github.silbaram.plan2agent.memory.application.port.out.VectorSearchPort
import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.KeywordSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactSummary
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.KeywordSearchMatch
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Repository
class PostgresArtifactQueryAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : ArtifactQueryPort {
    private val json = PostgresJsonSupport(objectMapper)
    private val cursorCodec = SearchCursorCodec(objectMapper)

    override fun findArtifacts(query: FindArtifactsQuery): PagedResult<ArtifactSummary> = metrics.recordSearch("artifact.find") {
        val params = MapSqlParameterSource()
            .addValue("limit", query.limit + 1)
        val filters = mutableListOf<String>()

        query.projectId?.let {
            filters += "project_id = :projectId"
            params.addValue("projectId", uuid(it.value))
        }
        query.iterationId?.let {
            filters += "iteration_id = :iterationId"
            params.addValue("iterationId", uuid(it.value))
        }
        query.sourceProjectId?.let {
            filters += "source_project_id = :sourceProjectId"
            params.addValue("sourceProjectId", it.value)
        }
        query.sourceIterationId?.let {
            filters += "source_iteration_id = :sourceIterationId"
            params.addValue("sourceIterationId", it.value)
        }
        query.sourceDocumentId?.let {
            filters += "source_document_id = :sourceDocumentId"
            params.addValue("sourceDocumentId", it.value)
        }
        query.sourceTaskGraphId?.let {
            filters += "source_task_graph_id = :sourceTaskGraphId"
            params.addValue("sourceTaskGraphId", it.value)
        }
        query.sourceTaskId?.let {
            filters += "source_task_id = :sourceTaskId"
            params.addValue("sourceTaskId", it.value)
        }
        query.sourceRunId?.let {
            filters += "source_run_id = :sourceRunId"
            params.addValue("sourceRunId", it.value)
        }
        query.artifactType?.let {
            filters += "artifact_type = :artifactType"
            params.addValue("artifactType", it.name)
        }
        query.sourcePath?.let {
            filters += "source_path = :sourcePath"
            params.addValue("sourcePath", it)
        }
        query.taskId?.let {
            filters += "task_id = :taskId"
            params.addValue("taskId", uuid(it.value))
        }
        query.runId?.let {
            filters += "run_id = :runId"
            params.addValue("runId", uuid(it.value))
        }
        query.contentHash?.let {
            filters += "content_hash = :contentHash"
            params.addValue("contentHash", it.value)
        }
        query.sourceReference?.let {
            filters += "source_ref_canonical_server_id = :sourceRefCanonicalServerId"
            filters += "source_ref_uri = :sourceRefUri"
            params.addValue("sourceRefCanonicalServerId", it.canonicalServerId.value)
            params.addValue("sourceRefUri", it.uri)
        }
        query.cursor?.let {
            val cursor = cursorCodec.decodeArtifact(it)
            filters += """
                (
                    sort_timestamp < :cursorSortTimestamp
                    OR (
                        sort_timestamp = :cursorSortTimestamp
                        AND artifact_type > :cursorArtifactType
                    )
                    OR (
                        sort_timestamp = :cursorSortTimestamp
                        AND artifact_type = :cursorArtifactType
                        AND artifact_id > :cursorArtifactId
                    )
                )
            """.trimIndent()
            params
                .addValue("cursorSortTimestamp", parseCursorTimestamp(cursor.sortTimestamp))
                .addValue("cursorArtifactType", cursor.artifactType)
                .addValue("cursorArtifactId", cursor.artifactId)
        }

        val whereClause = filters.toWhereClause()
        val rows = jdbc.query(
            """
            WITH artifacts AS (
                SELECT
                    'PROJECT' AS artifact_type,
                    p.project_id::text AS artifact_id,
                    p.project_id,
                    NULL::uuid AS iteration_id,
                    NULL::uuid AS task_id,
                    NULL::uuid AS run_id,
                    p.root_path AS source_path,
                    p.name AS title,
                    NULL::text AS content_hash,
                    p.metadata,
                    p.source_project_id,
                    NULL::text AS source_iteration_id,
                    NULL::text AS source_document_id,
                    NULL::text AS source_task_graph_id,
                    NULL::text AS source_task_id,
                    NULL::text AS source_run_id,
                    NULL::text AS source_chunk_id,
                    NULL::integer AS snapshot_version,
                    p.metadata ->> 'p2a.sourceReference.canonicalServerId' AS source_ref_canonical_server_id,
                    p.metadata ->> 'p2a.sourceReference.uri' AS source_ref_uri,
                    p.created_at,
                    p.updated_at,
                    p.created_at AS sort_timestamp
                FROM projects p
                UNION ALL
                SELECT
                    'ITERATION' AS artifact_type,
                    i.iteration_id::text AS artifact_id,
                    i.project_id,
                    i.iteration_id,
                    NULL::uuid AS task_id,
                    NULL::uuid AS run_id,
                    NULL::text AS source_path,
                    i.label AS title,
                    NULL::text AS content_hash,
                    i.metadata,
                    p.source_project_id,
                    i.source_iteration_id,
                    NULL::text AS source_document_id,
                    NULL::text AS source_task_graph_id,
                    NULL::text AS source_task_id,
                    NULL::text AS source_run_id,
                    NULL::text AS source_chunk_id,
                    NULL::integer AS snapshot_version,
                    i.metadata ->> 'p2a.sourceReference.canonicalServerId' AS source_ref_canonical_server_id,
                    i.metadata ->> 'p2a.sourceReference.uri' AS source_ref_uri,
                    i.created_at,
                    i.updated_at,
                    i.created_at AS sort_timestamp
                FROM iterations i
                JOIN projects p ON p.project_id = i.project_id
                UNION ALL
                SELECT
                    d.artifact_type,
                    d.document_id::text AS artifact_id,
                    d.project_id,
                    d.iteration_id,
                    NULL::uuid AS task_id,
                    NULL::uuid AS run_id,
                    d.source_path,
                    COALESCE(d.metadata ->> 'p2a.document.title', d.source_path) AS title,
                    d.content_hash,
                    d.metadata,
                    p.source_project_id,
                    i.source_iteration_id,
                    d.source_document_id,
                    NULL::text AS source_task_graph_id,
                    NULL::text AS source_task_id,
                    NULL::text AS source_run_id,
                    NULL::text AS source_chunk_id,
                    d.snapshot_version,
                    d.metadata ->> 'p2a.sourceReference.canonicalServerId' AS source_ref_canonical_server_id,
                    d.metadata ->> 'p2a.sourceReference.uri' AS source_ref_uri,
                    d.created_at,
                    d.updated_at,
                    d.created_at AS sort_timestamp
                FROM documents d
                JOIN projects p ON p.project_id = d.project_id
                LEFT JOIN iterations i ON i.iteration_id = d.iteration_id
                UNION ALL
                SELECT
                    'TASK_GRAPH' AS artifact_type,
                    tg.task_graph_id::text AS artifact_id,
                    tg.project_id,
                    tg.iteration_id,
                    NULL::uuid AS task_id,
                    NULL::uuid AS run_id,
                    d.source_path,
                    COALESCE(tg.source_task_graph_id, tg.task_graph_id::text) AS title,
                    tg.graph_hash AS content_hash,
                    tg.metadata,
                    p.source_project_id,
                    i.source_iteration_id,
                    COALESCE(tg.source_document_id, d.source_document_id),
                    tg.source_task_graph_id,
                    NULL::text AS source_task_id,
                    NULL::text AS source_run_id,
                    NULL::text AS source_chunk_id,
                    NULL::integer AS snapshot_version,
                    tg.metadata ->> 'p2a.sourceReference.canonicalServerId' AS source_ref_canonical_server_id,
                    tg.metadata ->> 'p2a.sourceReference.uri' AS source_ref_uri,
                    tg.created_at,
                    tg.updated_at,
                    tg.created_at AS sort_timestamp
                FROM task_graphs tg
                JOIN projects p ON p.project_id = tg.project_id
                JOIN iterations i ON i.iteration_id = tg.iteration_id
                LEFT JOIN documents d ON d.document_id = tg.document_id
                UNION ALL
                SELECT
                    'TASK' AS artifact_type,
                    t.task_id::text AS artifact_id,
                    t.project_id,
                    t.iteration_id,
                    t.task_id,
                    NULL::uuid AS run_id,
                    d.source_path,
                    t.title,
                    NULL::text AS content_hash,
                    t.metadata,
                    p.source_project_id,
                    i.source_iteration_id,
                    COALESCE(tg.source_document_id, d.source_document_id),
                    tg.source_task_graph_id,
                    t.source_task_id,
                    NULL::text AS source_run_id,
                    NULL::text AS source_chunk_id,
                    NULL::integer AS snapshot_version,
                    t.metadata ->> 'p2a.sourceReference.canonicalServerId' AS source_ref_canonical_server_id,
                    t.metadata ->> 'p2a.sourceReference.uri' AS source_ref_uri,
                    t.created_at,
                    t.updated_at,
                    t.created_at AS sort_timestamp
                FROM tasks t
                JOIN projects p ON p.project_id = t.project_id
                JOIN iterations i ON i.iteration_id = t.iteration_id
                JOIN task_graphs tg ON tg.task_graph_id = t.task_graph_id
                LEFT JOIN documents d ON d.document_id = tg.document_id
                UNION ALL
                SELECT
                    'RUN_RECORD' AS artifact_type,
                    r.run_id::text AS artifact_id,
                    r.project_id,
                    r.iteration_id,
                    r.task_id,
                    r.run_id,
                    NULL::text AS source_path,
                    COALESCE(r.source_run_id, r.run_id::text) AS title,
                    NULL::text AS content_hash,
                    r.metadata,
                    p.source_project_id,
                    i.source_iteration_id,
                    NULL::text AS source_document_id,
                    tg.source_task_graph_id,
                    t.source_task_id,
                    r.source_run_id,
                    NULL::text AS source_chunk_id,
                    NULL::integer AS snapshot_version,
                    r.metadata ->> 'p2a.sourceReference.canonicalServerId' AS source_ref_canonical_server_id,
                    r.metadata ->> 'p2a.sourceReference.uri' AS source_ref_uri,
                    r.created_at,
                    r.updated_at,
                    r.created_at AS sort_timestamp
                FROM runs r
                JOIN projects p ON p.project_id = r.project_id
                JOIN iterations i ON i.iteration_id = r.iteration_id
                JOIN tasks t ON t.task_id = r.task_id
                JOIN task_graphs tg ON tg.task_graph_id = t.task_graph_id
                UNION ALL
                SELECT
                    'DOCUMENT_CHUNK' AS artifact_type,
                    dc.chunk_id::text AS artifact_id,
                    dc.project_id,
                    dc.iteration_id,
                    dc.task_id,
                    dc.run_id,
                    dc.source_path,
                    dc.source_path || '#' || dc.chunk_index::text AS title,
                    dc.chunk_hash AS content_hash,
                    dc.metadata,
                    p.source_project_id,
                    i.source_iteration_id,
                    d.source_document_id,
                    tg.source_task_graph_id,
                    t.source_task_id,
                    r.source_run_id,
                    dc.source_chunk_id,
                    d.snapshot_version,
                    dc.metadata ->> 'p2a.sourceReference.canonicalServerId' AS source_ref_canonical_server_id,
                    dc.metadata ->> 'p2a.sourceReference.uri' AS source_ref_uri,
                    dc.created_at,
                    dc.updated_at,
                    dc.created_at AS sort_timestamp
                FROM document_chunks dc
                JOIN projects p ON p.project_id = dc.project_id
                LEFT JOIN iterations i ON i.iteration_id = dc.iteration_id
                JOIN documents d ON d.document_id = dc.document_id
                LEFT JOIN tasks t ON t.task_id = dc.task_id
                LEFT JOIN task_graphs tg ON tg.task_graph_id = t.task_graph_id
                LEFT JOIN runs r ON r.run_id = dc.run_id
            )
            SELECT *
            FROM artifacts
            $whereClause
            ORDER BY sort_timestamp DESC, artifact_type, artifact_id
            LIMIT :limit
            """.trimIndent(),
            params,
            artifactSummaryRowMapper(json),
        )
        rows.toPagedResult(query.limit, { it.summary }) { cursorCodec.encode(it.cursor) }
    }
}

@Repository
class PostgresKeywordSearchAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : KeywordSearchPort {
    private val json = PostgresJsonSupport(objectMapper)
    private val cursorCodec = SearchCursorCodec(objectMapper)

    override fun search(query: KeywordSearchQuery): PagedResult<KeywordSearchMatch> = metrics.recordSearch("keyword.search") {
        require(query.query.isNotBlank()) { "KeywordSearchQuery query must not be blank" }

        val params = searchParams(query)
            .addValue("keywordQuery", query.query.trim())
            .addValue("pattern", likePattern(query.query))
            .addValue("limit", query.limit + 1)
        if (query.metadataFilters.isNotEmpty()) {
            params.addValue("metadataFiltersJson", json.metadataToJson(query.metadataFilters))
        }
        val chunkWhere = chunkFilterClauses(query, params, alias = "dc")
            .withMetadataFilters(query.metadataFilters, alias = "dc")
            .toWhereClause(prefix = "WHERE")
        val documentWhere = documentFilterClauses(query, params, alias = "d")
            .withMetadataFilters(query.metadataFilters, alias = "d")
            .toWhereClause(prefix = "WHERE")
        val cursorWhere = keywordCursorWhere(query.cursor, params)

        val rows = jdbc.query(
            """
            WITH keyword_query AS (
                SELECT plainto_tsquery('simple', :keywordQuery) AS ts_query
            ),
            content_matches AS (
                SELECT
                    dc.chunk_id,
                    dc.document_id,
                    dc.project_id,
                    dc.iteration_id,
                    dc.artifact_type,
                    dc.source_path,
                    dc.chunk_index,
                    dc.content,
                    3.0 + ts_rank(to_tsvector('simple', dc.content), kq.ts_query) AS score,
                    'chunk.content' AS match_reason,
                    dc.metadata,
                    p.source_project_id,
                    i.source_iteration_id,
                    d.source_document_id,
                    tg.source_task_graph_id,
                    t.source_task_id,
                    r.source_run_id,
                    dc.source_chunk_id,
                    d.snapshot_version,
                    COALESCE(dc.updated_at, dc.created_at) AS sort_timestamp
                FROM document_chunks dc
                JOIN projects p ON p.project_id = dc.project_id
                LEFT JOIN iterations i ON i.iteration_id = dc.iteration_id
                JOIN documents d ON d.document_id = dc.document_id
                LEFT JOIN tasks t ON t.task_id = dc.task_id
                LEFT JOIN task_graphs tg ON tg.task_graph_id = t.task_graph_id
                LEFT JOIN runs r ON r.run_id = dc.run_id
                CROSS JOIN keyword_query kq
                $chunkWhere
                  AND to_tsvector('simple', dc.content) @@ kq.ts_query
                UNION ALL
                SELECT
                    NULL::uuid AS chunk_id,
                    d.document_id,
                    d.project_id,
                    d.iteration_id,
                    d.artifact_type,
                    d.source_path,
                    NULL::integer AS chunk_index,
                    d.content,
                    2.0 + ts_rank(to_tsvector('simple', d.content), kq.ts_query) AS score,
                    'document.content' AS match_reason,
                    d.metadata,
                    p.source_project_id,
                    i.source_iteration_id,
                    d.source_document_id,
                    NULL::text AS source_task_graph_id,
                    NULL::text AS source_task_id,
                    NULL::text AS source_run_id,
                    NULL::text AS source_chunk_id,
                    d.snapshot_version,
                    COALESCE(d.updated_at, d.created_at) AS sort_timestamp
                FROM documents d
                JOIN projects p ON p.project_id = d.project_id
                LEFT JOIN iterations i ON i.iteration_id = d.iteration_id
                CROSS JOIN keyword_query kq
                $documentWhere
                  AND to_tsvector('simple', d.content) @@ kq.ts_query
            ),
            secondary_matches AS (
                SELECT
                    dc.chunk_id,
                    dc.document_id,
                    dc.project_id,
                    dc.iteration_id,
                    dc.artifact_type,
                    dc.source_path,
                    dc.chunk_index,
                    dc.content,
                    CASE
                        WHEN lower(dc.source_path) LIKE :pattern ESCAPE '\' THEN 1.5
                        WHEN lower(dc.artifact_type) LIKE :pattern ESCAPE '\' THEN 1.0
                        ELSE 0.5
                    END AS score,
                    CASE
                        WHEN lower(dc.source_path) LIKE :pattern ESCAPE '\' THEN 'sourcePath'
                        WHEN lower(dc.artifact_type) LIKE :pattern ESCAPE '\' THEN 'artifactType'
                        ELSE 'metadata'
                    END AS match_reason,
                    dc.metadata,
                    p.source_project_id,
                    i.source_iteration_id,
                    d.source_document_id,
                    tg.source_task_graph_id,
                    t.source_task_id,
                    r.source_run_id,
                    dc.source_chunk_id,
                    d.snapshot_version,
                    COALESCE(dc.updated_at, dc.created_at) AS sort_timestamp
                FROM document_chunks dc
                JOIN projects p ON p.project_id = dc.project_id
                LEFT JOIN iterations i ON i.iteration_id = dc.iteration_id
                JOIN documents d ON d.document_id = dc.document_id
                LEFT JOIN tasks t ON t.task_id = dc.task_id
                LEFT JOIN task_graphs tg ON tg.task_graph_id = t.task_graph_id
                LEFT JOIN runs r ON r.run_id = dc.run_id
                CROSS JOIN keyword_query kq
                $chunkWhere
                  AND NOT (to_tsvector('simple', dc.content) @@ kq.ts_query)
                  AND (
                    lower(dc.source_path) LIKE :pattern ESCAPE '\'
                    OR lower(dc.artifact_type) LIKE :pattern ESCAPE '\'
                  )
                UNION ALL
                SELECT
                    NULL::uuid AS chunk_id,
                    d.document_id,
                    d.project_id,
                    d.iteration_id,
                    d.artifact_type,
                    d.source_path,
                    NULL::integer AS chunk_index,
                    d.content,
                    CASE
                        WHEN lower(d.source_path) LIKE :pattern ESCAPE '\' THEN 1.5
                        WHEN lower(d.artifact_type) LIKE :pattern ESCAPE '\' THEN 1.0
                        ELSE 0.5
                    END AS score,
                    CASE
                        WHEN lower(d.source_path) LIKE :pattern ESCAPE '\' THEN 'sourcePath'
                        WHEN lower(d.artifact_type) LIKE :pattern ESCAPE '\' THEN 'artifactType'
                        ELSE 'metadata'
                    END AS match_reason,
                    d.metadata,
                    p.source_project_id,
                    i.source_iteration_id,
                    d.source_document_id,
                    NULL::text AS source_task_graph_id,
                    NULL::text AS source_task_id,
                    NULL::text AS source_run_id,
                    NULL::text AS source_chunk_id,
                    d.snapshot_version,
                    COALESCE(d.updated_at, d.created_at) AS sort_timestamp
                FROM documents d
                JOIN projects p ON p.project_id = d.project_id
                LEFT JOIN iterations i ON i.iteration_id = d.iteration_id
                CROSS JOIN keyword_query kq
                $documentWhere
                  AND NOT (to_tsvector('simple', d.content) @@ kq.ts_query)
                  AND (
                    lower(d.source_path) LIKE :pattern ESCAPE '\'
                    OR lower(d.artifact_type) LIKE :pattern ESCAPE '\'
                  )
            ),
            matches AS (
                SELECT * FROM content_matches
                UNION ALL
                SELECT * FROM secondary_matches
            )
            SELECT *
            FROM matches
            $cursorWhere
            ORDER BY
                score DESC,
                COALESCE(snapshot_version, -1) DESC,
                sort_timestamp DESC,
                COALESCE(chunk_index, 2147483647) ASC,
                document_id::text ASC,
                COALESCE(chunk_id::text, '') ASC
            LIMIT :limit
            """.trimIndent(),
            params,
            keywordSearchRowMapper(json),
        )
        rows.toPagedResult(query.limit, { it.match }) { cursorCodec.encode(it.cursor) }
    }

    private fun keywordCursorWhere(cursorValue: String?, params: MapSqlParameterSource): String {
        val cursor = cursorValue?.let(cursorCodec::decodeKeyword) ?: return ""
        params
            .addValue("cursorScore", cursor.score)
            .addValue("cursorSnapshotSort", cursor.snapshotSort)
            .addValue("cursorSortTimestamp", parseCursorTimestamp(cursor.sortTimestamp))
            .addValue("cursorChunkIndexSort", cursor.chunkIndexSort)
            .addValue("cursorDocumentId", cursor.documentId)
            .addValue("cursorChunkId", cursor.chunkId)
        return """
            WHERE (
                score < :cursorScore
                OR (
                    score = :cursorScore
                    AND COALESCE(snapshot_version, -1) < :cursorSnapshotSort
                )
                OR (
                    score = :cursorScore
                    AND COALESCE(snapshot_version, -1) = :cursorSnapshotSort
                    AND sort_timestamp < :cursorSortTimestamp
                )
                OR (
                    score = :cursorScore
                    AND COALESCE(snapshot_version, -1) = :cursorSnapshotSort
                    AND sort_timestamp = :cursorSortTimestamp
                    AND COALESCE(chunk_index, 2147483647) > :cursorChunkIndexSort
                )
                OR (
                    score = :cursorScore
                    AND COALESCE(snapshot_version, -1) = :cursorSnapshotSort
                    AND sort_timestamp = :cursorSortTimestamp
                    AND COALESCE(chunk_index, 2147483647) = :cursorChunkIndexSort
                    AND document_id::text > :cursorDocumentId
                )
                OR (
                    score = :cursorScore
                    AND COALESCE(snapshot_version, -1) = :cursorSnapshotSort
                    AND sort_timestamp = :cursorSortTimestamp
                    AND COALESCE(chunk_index, 2147483647) = :cursorChunkIndexSort
                    AND document_id::text = :cursorDocumentId
                    AND COALESCE(chunk_id::text, '') > :cursorChunkId
                )
            )
        """.trimIndent()
    }
}

@Repository
class PostgresVectorSearchAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : VectorSearchPort {
    private val json = PostgresJsonSupport(objectMapper)
    private val cursorCodec = SearchCursorCodec(objectMapper)

    override fun search(query: VectorSearchQuery): PagedResult<VectorSearchMatch> = metrics.recordSearch("vector.search") {
        require(query.embedding.values.isNotEmpty()) { "VectorSearchQuery embedding must not be empty" }
        require(query.embedding.values.size == query.embeddingDimension) {
            "VectorSearchQuery embeddingDimension must match embedding size"
        }
        require(query.embeddingDimension <= MAX_VECTOR_DIMENSION) {
            "VectorSearchQuery embeddingDimension must be <= $MAX_VECTOR_DIMENSION for pgvector storage"
        }
        require(query.embeddingModel.isNotBlank()) { "VectorSearchQuery embeddingModel must not be blank" }
        require(query.embeddingVersion.isNotBlank()) { "VectorSearchQuery embeddingVersion must not be blank" }
        validateStoredEmbeddingDimensions(query)

        val params = searchParams(query)
            .addValue("embeddingModel", query.embeddingModel)
            .addValue("embeddingDimension", query.embeddingDimension)
            .addValue("embeddingVersion", query.embeddingVersion)
            .addValue("distanceMetric", query.distanceMetric.toDbValue())
            .addValue("queryEmbedding", query.embedding.toPgVectorLiteral())
            .addValue("limit", query.limit + 1)
        if (query.metadataFilters.isNotEmpty()) {
            params.addValue("metadataFiltersJson", json.metadataToJson(query.metadataFilters))
        }
        val chunkWhere = chunkFilterClauses(query, params, alias = "dc")
            .withMetadataFilters(query.metadataFilters, alias = "dc")
            .toWhereClause(prefix = "WHERE")
        val typedVectorSource = typedVectorSource(query.embeddingDimension)
        val embeddingColumn = typedVectorSource?.let { "tev.embedding" } ?: "ce.embedding"
        val queryVectorType = typedVectorSource?.vectorType ?: "vector"
        val typedVectorJoin = typedVectorSource?.let {
            "JOIN ${it.tableName} tev ON tev.chunk_embedding_id = ce.chunk_embedding_id"
        } ?: ""
        val distanceExpression = query.distanceMetric.distanceExpression(embeddingColumn, queryVectorType)
        val orderExpression = query.distanceMetric.orderExpression(embeddingColumn, queryVectorType)
        val cursorWhere = vectorCursorWhere(query.cursor, params, orderExpression)

        val rows = jdbc.query(
            """
            SELECT
                dc.chunk_id,
                dc.document_id,
                dc.project_id,
                dc.iteration_id,
                dc.artifact_type,
                dc.source_path,
                dc.chunk_index,
                dc.content,
                $distanceExpression AS score,
                $orderExpression AS sort_value,
                es.distance_metric,
                es.embedding_model,
                es.embedding_version,
                dc.metadata,
                p.source_project_id,
                i.source_iteration_id,
                d.source_document_id,
                tg.source_task_graph_id,
                t.source_task_id,
                r.source_run_id,
                dc.source_chunk_id,
                d.snapshot_version,
                COALESCE(dc.updated_at, dc.created_at) AS sort_timestamp
            FROM chunk_embeddings ce
            $typedVectorJoin
            JOIN embedding_sets es ON es.embedding_set_id = ce.embedding_set_id
            JOIN document_chunks dc ON dc.chunk_id = ce.chunk_id
            JOIN projects p ON p.project_id = dc.project_id
            LEFT JOIN iterations i ON i.iteration_id = dc.iteration_id
            JOIN documents d ON d.document_id = dc.document_id
            LEFT JOIN tasks t ON t.task_id = dc.task_id
            LEFT JOIN task_graphs tg ON tg.task_graph_id = t.task_graph_id
            LEFT JOIN runs r ON r.run_id = dc.run_id
            $chunkWhere
              AND es.embedding_model = :embeddingModel
              AND es.embedding_dimension = :embeddingDimension
              AND es.embedding_version = :embeddingVersion
              AND es.distance_metric = :distanceMetric
              $cursorWhere
            ORDER BY
                sort_value ASC,
                COALESCE(d.snapshot_version, -1) DESC,
                sort_timestamp DESC,
                COALESCE(dc.chunk_index, 2147483647) ASC,
                dc.chunk_id::text ASC
            LIMIT :limit
            """.trimIndent(),
            params,
            vectorSearchRowMapper(json),
        )
        rows.toPagedResult(query.limit, { it.match }) { cursorCodec.encode(it.cursor) }
    }

    private fun vectorCursorWhere(
        cursorValue: String?,
        params: MapSqlParameterSource,
        orderExpression: String,
    ): String {
        val cursor = cursorValue?.let(cursorCodec::decodeVector) ?: return ""
        params
            .addValue("cursorSortValue", cursor.sortValue)
            .addValue("cursorSnapshotSort", cursor.snapshotSort)
            .addValue("cursorSortTimestamp", parseCursorTimestamp(cursor.sortTimestamp))
            .addValue("cursorChunkIndexSort", cursor.chunkIndexSort)
            .addValue("cursorChunkId", cursor.chunkId)
        return """
            AND (
                $orderExpression > :cursorSortValue
                OR (
                    $orderExpression = :cursorSortValue
                    AND COALESCE(d.snapshot_version, -1) < :cursorSnapshotSort
                )
                OR (
                    $orderExpression = :cursorSortValue
                    AND COALESCE(d.snapshot_version, -1) = :cursorSnapshotSort
                    AND COALESCE(dc.updated_at, dc.created_at) < :cursorSortTimestamp
                )
                OR (
                    $orderExpression = :cursorSortValue
                    AND COALESCE(d.snapshot_version, -1) = :cursorSnapshotSort
                    AND COALESCE(dc.updated_at, dc.created_at) = :cursorSortTimestamp
                    AND COALESCE(dc.chunk_index, 2147483647) > :cursorChunkIndexSort
                )
                OR (
                    $orderExpression = :cursorSortValue
                    AND COALESCE(d.snapshot_version, -1) = :cursorSnapshotSort
                    AND COALESCE(dc.updated_at, dc.created_at) = :cursorSortTimestamp
                    AND COALESCE(dc.chunk_index, 2147483647) = :cursorChunkIndexSort
                    AND dc.chunk_id::text > :cursorChunkId
                )
            )
        """.trimIndent()
    }

    private fun validateStoredEmbeddingDimensions(query: VectorSearchQuery) {
        val dimensions = jdbc.query(
            """
            SELECT DISTINCT embedding_dimension
            FROM embedding_sets
            WHERE embedding_model = :embeddingModel
              AND embedding_version = :embeddingVersion
              AND distance_metric = :distanceMetric
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("embeddingModel", query.embeddingModel)
                .addValue("embeddingVersion", query.embeddingVersion)
                .addValue("distanceMetric", query.distanceMetric.toDbValue()),
        ) { rs, _ -> rs.getInt("embedding_dimension") }

        if (dimensions.isNotEmpty() && query.embeddingDimension !in dimensions) {
            throw IllegalArgumentException(
                "VectorSearchQuery embeddingDimension ${query.embeddingDimension} does not match stored embedding set dimensions $dimensions",
            )
        }
    }
}

private fun searchParams(query: KeywordSearchQuery): MapSqlParameterSource =
    MapSqlParameterSource()
        .addNullableUuid("projectId", query.projectId)
        .addNullableUuid("iterationId", query.iterationId)
        .addValue("artifactType", query.artifactType?.name)
        .addValue("sourcePath", query.sourcePath)
        .addNullableUuid("taskId", query.taskId)
        .addNullableUuid("runId", query.runId)

private fun searchParams(query: VectorSearchQuery): MapSqlParameterSource =
    MapSqlParameterSource()
        .addNullableUuid("projectId", query.projectId)
        .addNullableUuid("iterationId", query.iterationId)
        .addValue("artifactType", query.artifactType?.name)
        .addValue("sourcePath", query.sourcePath)
        .addNullableUuid("taskId", query.taskId)
        .addNullableUuid("runId", query.runId)

private fun chunkFilterClauses(
    query: KeywordSearchQuery,
    params: MapSqlParameterSource,
    alias: String,
): List<String> =
    searchFilterClauses(query.projectId, query.iterationId, query.artifactType, query.sourcePath, query.taskId, query.runId, alias)

private fun chunkFilterClauses(
    query: VectorSearchQuery,
    params: MapSqlParameterSource,
    alias: String,
): List<String> =
    searchFilterClauses(query.projectId, query.iterationId, query.artifactType, query.sourcePath, query.taskId, query.runId, alias)

private fun documentFilterClauses(
    query: KeywordSearchQuery,
    params: MapSqlParameterSource,
    alias: String,
): List<String> {
    val filters = searchFilterClauses(query.projectId, query.iterationId, query.artifactType, query.sourcePath, null, null, alias)
        .toMutableList()
    if (query.taskId != null || query.runId != null) {
        filters += "FALSE"
    }
    return filters
}

private fun searchFilterClauses(
    projectId: ProjectId?,
    iterationId: IterationId?,
    artifactType: ArtifactType?,
    sourcePath: String?,
    taskId: TaskId?,
    runId: RunId?,
    alias: String,
): List<String> =
    buildList {
        projectId?.let { add("$alias.project_id = :projectId") }
        iterationId?.let { add("$alias.iteration_id = :iterationId") }
        artifactType?.let { add("$alias.artifact_type = :artifactType") }
        sourcePath?.let { add("$alias.source_path = :sourcePath") }
        taskId?.let { add("$alias.task_id = :taskId") }
        runId?.let { add("$alias.run_id = :runId") }
    }

private fun List<String>.withMetadataFilters(metadataFilters: Map<String, String>, alias: String): List<String> =
    if (metadataFilters.isEmpty()) {
        this
    } else {
        this + "$alias.metadata @> CAST(:metadataFiltersJson AS jsonb)"
    }

private fun List<String>.toWhereClause(prefix: String = "WHERE"): String =
    if (isEmpty()) {
        "$prefix TRUE"
    } else {
        joinToString(separator = "\n  AND ", prefix = "$prefix ")
    }

private data class ArtifactSummaryRow(
    val summary: ArtifactSummary,
    val cursor: ArtifactCursor,
)

private data class KeywordSearchRow(
    val match: KeywordSearchMatch,
    val cursor: KeywordCursor,
)

private data class VectorSearchRow(
    val match: VectorSearchMatch,
    val cursor: VectorCursor,
)

private fun artifactSummaryRowMapper(json: PostgresJsonSupport): RowMapper<ArtifactSummaryRow> =
    RowMapper { rs, _ ->
        val summary = artifactSummary(json, rs)
        ArtifactSummaryRow(
            summary = summary,
            cursor = ArtifactCursor(
                sortTimestamp = rs.instant("sort_timestamp").toString(),
                artifactType = rs.getString("artifact_type"),
                artifactId = rs.getString("artifact_id"),
            ),
        )
    }

private fun artifactSummary(json: PostgresJsonSupport, rs: ResultSet): ArtifactSummary {
    val metadata = json.metadataFromJson(rs.getString("metadata"))
    return ArtifactSummary(
        artifactType = ArtifactType.valueOf(rs.getString("artifact_type")),
        artifactId = rs.getString("artifact_id"),
        projectId = ProjectId(rs.getString("project_id")),
        iterationId = rs.getString("iteration_id")?.let(::IterationId),
        taskId = rs.getString("task_id")?.let(::TaskId),
        runId = rs.getString("run_id")?.let(::RunId),
        sourcePath = rs.getString("source_path"),
        title = rs.getString("title"),
        contentHash = rs.getString("content_hash")?.let(::ContentHash),
        sourceReference = json.sourceReferenceFrom(metadata),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.nullableInstant("updated_at"),
        metadata = sourceMetadata(json, metadata, rs),
    )
}

private fun keywordSearchRowMapper(json: PostgresJsonSupport): RowMapper<KeywordSearchRow> =
    RowMapper { rs, _ ->
        val match = keywordSearchMatch(json, rs)
        KeywordSearchRow(
            match = match,
            cursor = KeywordCursor(
                score = rs.getDouble("score"),
                snapshotSort = rs.nullableInt("snapshot_version") ?: -1,
                sortTimestamp = rs.instant("sort_timestamp").toString(),
                chunkIndexSort = rs.nullableInt("chunk_index") ?: NULLS_LAST_INT,
                documentId = rs.getString("document_id"),
                chunkId = rs.getString("chunk_id") ?: "",
            ),
        )
    }

private fun keywordSearchMatch(json: PostgresJsonSupport, rs: ResultSet): KeywordSearchMatch {
    val metadata = json.metadataFromJson(rs.getString("metadata"))
    return KeywordSearchMatch(
        chunkId = rs.getString("chunk_id")?.let(::DocumentChunkId),
        documentId = rs.getString("document_id")?.let(::DocumentId),
        projectId = ProjectId(rs.getString("project_id")),
        iterationId = rs.getString("iteration_id")?.let(::IterationId),
        artifactType = ArtifactType.valueOf(rs.getString("artifact_type")),
        sourcePath = rs.getString("source_path"),
        chunkIndex = rs.nullableInt("chunk_index"),
        content = rs.getString("content"),
        score = rs.getDouble("score"),
        matchReason = rs.getString("match_reason"),
        metadata = sourceMetadata(json, metadata, rs),
        sourceReference = json.sourceReferenceFrom(metadata),
    )
}

private fun vectorSearchRowMapper(json: PostgresJsonSupport): RowMapper<VectorSearchRow> =
    RowMapper { rs, _ ->
        val match = vectorSearchMatch(json, rs)
        VectorSearchRow(
            match = match,
            cursor = VectorCursor(
                sortValue = rs.getDouble("sort_value"),
                snapshotSort = rs.nullableInt("snapshot_version") ?: -1,
                sortTimestamp = rs.instant("sort_timestamp").toString(),
                chunkIndexSort = rs.nullableInt("chunk_index") ?: NULLS_LAST_INT,
                chunkId = rs.getString("chunk_id"),
            ),
        )
    }

private fun vectorSearchMatch(json: PostgresJsonSupport, rs: ResultSet): VectorSearchMatch {
    val metadata = json.metadataFromJson(rs.getString("metadata"))
    return VectorSearchMatch(
        chunkId = rs.getString("chunk_id")?.let(::DocumentChunkId),
        documentId = rs.getString("document_id")?.let(::DocumentId),
        projectId = ProjectId(rs.getString("project_id")),
        iterationId = rs.getString("iteration_id")?.let(::IterationId),
        artifactType = ArtifactType.valueOf(rs.getString("artifact_type")),
        sourcePath = rs.getString("source_path"),
        chunkIndex = rs.nullableInt("chunk_index"),
        content = rs.getString("content"),
        score = rs.getDouble("score"),
        distanceMetric = distanceMetricFromDbValue(rs.getString("distance_metric")),
        embeddingModel = rs.getString("embedding_model"),
        embeddingVersion = rs.getString("embedding_version"),
        metadata = sourceMetadata(json, metadata, rs),
        sourceReference = json.sourceReferenceFrom(metadata),
    )
}

private data class ArtifactCursor(
    val kind: String = ARTIFACT_CURSOR_KIND,
    val sortTimestamp: String,
    val artifactType: String,
    val artifactId: String,
)

private data class KeywordCursor(
    val kind: String = KEYWORD_CURSOR_KIND,
    val score: Double,
    val snapshotSort: Int,
    val sortTimestamp: String,
    val chunkIndexSort: Int,
    val documentId: String,
    val chunkId: String,
)

private data class VectorCursor(
    val kind: String = VECTOR_CURSOR_KIND,
    val sortValue: Double,
    val snapshotSort: Int,
    val sortTimestamp: String,
    val chunkIndexSort: Int,
    val chunkId: String,
)

private class SearchCursorCodec(
    private val objectMapper: ObjectMapper,
) {
    fun encode(cursor: ArtifactCursor): String = encodeCursor(cursor)

    fun encode(cursor: KeywordCursor): String = encodeCursor(cursor)

    fun encode(cursor: VectorCursor): String = encodeCursor(cursor)

    fun decodeArtifact(value: String): ArtifactCursor =
        decodeCursor(value, ArtifactCursor::class.java).also {
            require(it.kind == ARTIFACT_CURSOR_KIND) { "cursor does not belong to artifact lookup" }
        }

    fun decodeKeyword(value: String): KeywordCursor =
        decodeCursor(value, KeywordCursor::class.java).also {
            require(it.kind == KEYWORD_CURSOR_KIND) { "cursor does not belong to keyword search" }
        }

    fun decodeVector(value: String): VectorCursor =
        decodeCursor(value, VectorCursor::class.java).also {
            require(it.kind == VECTOR_CURSOR_KIND) { "cursor does not belong to vector search" }
        }

    private fun encodeCursor(cursor: Any): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(objectMapper.writeValueAsBytes(cursor))

    private fun <T> decodeCursor(value: String, type: Class<T>): T {
        val decoded = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("cursor has invalid format")
        }
        return try {
            objectMapper.readValue(decoded, type)
        } catch (_: Exception) {
            throw IllegalArgumentException("cursor has invalid format")
        }
    }
}

private fun <R, T> List<R>.toPagedResult(
    limit: Int,
    value: (R) -> T,
    cursor: (R) -> String,
): PagedResult<T> {
    val pageRows = if (size > limit) take(limit) else this
    val nextCursor = if (size > limit) cursor(pageRows.last()) else null
    return PagedResult(
        items = pageRows.map(value),
        nextCursor = nextCursor,
    )
}

private fun parseCursorTimestamp(value: String): Timestamp =
    try {
        Timestamp.from(Instant.parse(value))
    } catch (_: Exception) {
        throw IllegalArgumentException("cursor has invalid format")
    }

private fun sourceMetadata(
    json: PostgresJsonSupport,
    metadata: Map<String, String>,
    rs: ResultSet,
): Map<String, String> =
    json.withoutReservedMetadata(metadata) + listOfNotNull(
        rs.getString("source_project_id")?.let { "sourceProjectId" to it },
        rs.getString("source_iteration_id")?.let { "sourceIterationId" to it },
        rs.getString("source_document_id")?.let { "sourceDocumentId" to it },
        rs.getString("source_task_graph_id")?.let { "sourceTaskGraphId" to it },
        rs.getString("source_task_id")?.let { "sourceTaskId" to it },
        rs.getString("source_run_id")?.let { "sourceRunId" to it },
        rs.getString("source_chunk_id")?.let { "sourceChunkId" to it },
        rs.nullableInt("snapshot_version")?.toString()?.let { "snapshotVersion" to it },
    )

private fun likePattern(value: String): String =
    "%" + value.trim().lowercase()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_") + "%"

private fun MapSqlParameterSource.addNullableUuid(name: String, value: ProjectId?): MapSqlParameterSource =
    addValue(name, value?.value?.let(::uuid))

private fun MapSqlParameterSource.addNullableUuid(name: String, value: IterationId?): MapSqlParameterSource =
    addValue(name, value?.value?.let(::uuid))

private fun MapSqlParameterSource.addNullableUuid(name: String, value: TaskId?): MapSqlParameterSource =
    addValue(name, value?.value?.let(::uuid))

private fun MapSqlParameterSource.addNullableUuid(name: String, value: RunId?): MapSqlParameterSource =
    addValue(name, value?.value?.let(::uuid))

private fun DistanceMetric.toDbValue(): String =
    when (this) {
        DistanceMetric.COSINE -> "cosine"
        DistanceMetric.INNER_PRODUCT -> "inner_product"
        DistanceMetric.L2 -> "l2"
    }

private fun distanceMetricFromDbValue(value: String): DistanceMetric =
    when (value) {
        "cosine" -> DistanceMetric.COSINE
        "inner_product" -> DistanceMetric.INNER_PRODUCT
        "l2" -> DistanceMetric.L2
        else -> error("Unsupported distance metric: $value")
    }

private data class TypedVectorSource(
    val tableName: String,
    val vectorType: String,
)

private fun typedVectorSource(embeddingDimension: Int): TypedVectorSource? =
    when (embeddingDimension) {
        2 -> TypedVectorSource(tableName = "chunk_embedding_vectors_2", vectorType = "vector(2)")
        1536 -> TypedVectorSource(tableName = "chunk_embedding_vectors_1536", vectorType = "vector(1536)")
        else -> null
    }

private fun DistanceMetric.distanceExpression(embeddingColumn: String, queryVectorType: String): String =
    when (this) {
        DistanceMetric.COSINE -> "($embeddingColumn <=> CAST(:queryEmbedding AS $queryVectorType))"
        DistanceMetric.L2 -> "($embeddingColumn <-> CAST(:queryEmbedding AS $queryVectorType))"
        DistanceMetric.INNER_PRODUCT -> "GREATEST(0.0, ($embeddingColumn <#> CAST(:queryEmbedding AS $queryVectorType)) * -1)"
    }

private fun DistanceMetric.orderExpression(embeddingColumn: String, queryVectorType: String): String =
    when (this) {
        DistanceMetric.COSINE -> "($embeddingColumn <=> CAST(:queryEmbedding AS $queryVectorType))"
        DistanceMetric.L2 -> "($embeddingColumn <-> CAST(:queryEmbedding AS $queryVectorType))"
        DistanceMetric.INNER_PRODUCT -> "($embeddingColumn <#> CAST(:queryEmbedding AS $queryVectorType))"
    }

private fun Embedding.toPgVectorLiteral(): String {
    require(values.isNotEmpty()) { "Embedding must contain at least one dimension" }
    return values.joinToString(separator = ",", prefix = "[", postfix = "]") {
        require(!it.isNaN() && !it.isInfinite()) { "Embedding values must be finite" }
        it.toString()
    }
}

private fun uuid(value: String): UUID =
    UUID.fromString(value)

private fun ResultSet.nullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

private fun ResultSet.instant(column: String): Instant =
    getTimestamp(column).toInstant()

private fun ResultSet.nullableInstant(column: String): Instant? =
    getTimestamp(column)?.toInstant()

private const val ARTIFACT_CURSOR_KIND = "artifact.v1"
private const val KEYWORD_CURSOR_KIND = "keyword.v1"
private const val VECTOR_CURSOR_KIND = "vector.v1"
private const val NULLS_LAST_INT = 2147483647
private const val MAX_VECTOR_DIMENSION = 2000
