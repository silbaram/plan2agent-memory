package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactGraphStorePort
import com.github.silbaram.plan2agent.memory.application.usecase.ArtifactGraphSnapshotResult
import com.github.silbaram.plan2agent.memory.application.usecase.GraphNodeSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.GraphTraceDirection
import com.github.silbaram.plan2agent.memory.application.usecase.GraphTraceQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactEdge
import com.github.silbaram.plan2agent.memory.domain.ArtifactEdgeId
import com.github.silbaram.plan2agent.memory.domain.ArtifactEdgeType
import com.github.silbaram.plan2agent.memory.domain.ArtifactNode
import com.github.silbaram.plan2agent.memory.domain.ArtifactNodeId
import com.github.silbaram.plan2agent.memory.domain.ArtifactNodeKind
import com.github.silbaram.plan2agent.memory.domain.ArtifactTrace
import com.github.silbaram.plan2agent.memory.domain.ArtifactTraceNode
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class PostgresArtifactGraphAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : ArtifactGraphStorePort {
    private val json = PostgresJsonSupport(objectMapper)

    override fun replaceSnapshot(projectId: ProjectId, iterationId: IterationId?, nodes: List<ArtifactNode>, edges: List<ArtifactEdge>): ArtifactGraphSnapshotResult = metrics.recordWrite("artifact_graph.replace") {
        jdbc.update("DELETE FROM artifact_nodes WHERE project_id = :projectId AND iteration_id IS NOT DISTINCT FROM :iterationId", params(projectId, iterationId))
        nodes.forEach { n -> jdbc.update("""
            INSERT INTO artifact_nodes (node_id, project_id, iteration_id, node_kind, natural_key, label, content, document_id, task_id, run_id, metadata, updated_at)
            VALUES (:id, :projectId, :iterationId, :kind, :naturalKey, :label, :content, :documentId, :taskId, :runId, CAST(:metadata AS jsonb), now())
        """.trimIndent(), nodeParams(n)) }
        edges.forEach { e ->
            jdbc.update("""
                INSERT INTO artifact_edges (edge_id, project_id, from_node_id, to_node_id, edge_type, source_reference, metadata, updated_at)
                VALUES (:id, :projectId, :fromNodeId, :toNodeId, :type, :sourceReference, CAST(:metadata AS jsonb), now())
            """.trimIndent(), edgeParams(e))
        }
        ArtifactGraphSnapshotResult(nodes.size, edges.size)
    }

    override fun findNodes(query: GraphNodeSearchQuery): List<ArtifactNode> {
        val where = mutableListOf("project_id = :projectId")
        val p = params(query.projectId, query.iterationId).addValue("limit", query.limit)
        query.iterationId?.let { where += "iteration_id = :iterationId" }
        query.nodeKind?.let { where += "node_kind = :nodeKind"; p.addValue("nodeKind", it.db()) }
        query.query?.let { where += "(label ILIKE :q OR content ILIKE :q)"; p.addValue("q", "%$it%") }
        return jdbc.query("SELECT * FROM artifact_nodes WHERE ${where.joinToString(" AND ")} ORDER BY natural_key LIMIT :limit", p, nodeMapper(json))
    }

    override fun trace(query: GraphTraceQuery): ArtifactTrace {
        val root = resolveRoot(query)
        val clauses = when (query.direction) {
            GraphTraceDirection.UPSTREAM -> "JOIN walk w ON e.from_node_id = w.node_id JOIN artifact_nodes n ON n.node_id = e.to_node_id"
            GraphTraceDirection.DOWNSTREAM -> "JOIN walk w ON e.to_node_id = w.node_id JOIN artifact_nodes n ON n.node_id = e.from_node_id"
            GraphTraceDirection.BOTH -> "JOIN walk w ON e.from_node_id = w.node_id OR e.to_node_id = w.node_id JOIN artifact_nodes n ON n.node_id = CASE WHEN e.from_node_id = w.node_id THEN e.to_node_id ELSE e.from_node_id END"
        }
        val p = MapSqlParameterSource()
            .addValue("projectId", uuid(query.projectId.value))
            .addValue("root", uuid(root.id.value))
            .addValue("maxDepth", query.maxDepth)
            .addValue("traversalDepth", query.maxDepth + 1)
        val allNodeDepths = jdbc.query("""
            WITH RECURSIVE walk(node_id, depth, path) AS (
              SELECT CAST(:root AS uuid), 0, ARRAY[CAST(:root AS uuid)]
              UNION ALL
              SELECT n.node_id, w.depth + 1, path || n.node_id
              FROM artifact_edges e $clauses
              WHERE e.project_id = :projectId AND w.depth < :traversalDepth AND NOT n.node_id = ANY(path)
            )
            SELECT n.*, MIN(w.depth) AS depth FROM walk w JOIN artifact_nodes n ON n.node_id = w.node_id GROUP BY n.node_id ORDER BY depth, natural_key
        """.trimIndent(), p) { rs, _ -> ArtifactTraceNode(nodeMapper(json).mapRow(rs, 0)!!, rs.getInt("depth")) }
        val nodeDepths = allNodeDepths.filter { it.depth <= query.maxDepth }
        val ids = nodeDepths.map { uuid(it.node.id.value) }
        val traceEdges = if (ids.isEmpty()) emptyList() else jdbc.query("SELECT * FROM artifact_edges WHERE project_id = :projectId AND from_node_id IN (:ids) AND to_node_id IN (:ids)", MapSqlParameterSource().addValue("projectId", uuid(query.projectId.value)).addValue("ids", ids), edgeMapper(json))
        val truncated = allNodeDepths.any { it.depth > query.maxDepth }
        return ArtifactTrace(root, nodeDepths, traceEdges, truncated)
    }

    private fun resolveRoot(query: GraphTraceQuery): ArtifactNode {
        val where = mutableListOf("project_id = :projectId", "natural_key = :naturalKey")
        val p = params(query.projectId, query.iterationId).addValue("naturalKey", query.naturalKey)
        query.iterationId?.let { where += "iteration_id = :iterationId" }
        val roots = jdbc.query(
            "SELECT * FROM artifact_nodes WHERE ${where.joinToString(" AND ")} ORDER BY iteration_id NULLS FIRST, node_id LIMIT 2",
            p,
            nodeMapper(json),
        )
        require(roots.isNotEmpty()) { "Artifact graph root naturalKey ${query.naturalKey} was not found" }
        require(roots.size == 1) {
            "Artifact graph root naturalKey ${query.naturalKey} is ambiguous; supply iterationId"
        }
        return roots.single()
    }

    private fun params(projectId: ProjectId, iterationId: IterationId?) = MapSqlParameterSource().addValue("projectId", uuid(projectId.value)).addValue("iterationId", iterationId?.value?.let(::uuid))
    private fun nodeParams(n: ArtifactNode) = params(n.projectId, n.iterationId).addValue("id", uuid(n.id.value)).addValue("kind", n.kind.db()).addValue("naturalKey", n.naturalKey).addValue("label", n.label).addValue("content", n.content).addValue("documentId", n.documentId?.value?.let(::uuid)).addValue("taskId", n.taskId?.value?.let(::uuid)).addValue("runId", n.runId?.value?.let(::uuid)).addValue("metadata", json.metadataToJson(n.metadata))
    private fun edgeParams(e: ArtifactEdge) = MapSqlParameterSource().addValue("id", uuid(e.id.value)).addValue("projectId", uuid(e.projectId.value)).addValue("fromNodeId", uuid(e.fromNodeId.value)).addValue("toNodeId", uuid(e.toNodeId.value)).addValue("type", e.type.name).addValue("sourceReference", e.sourceReference).addValue("metadata", json.metadataToJson(e.metadata))
}

private fun ArtifactNodeKind.db(): String = name.lowercase()
private fun nodeKind(value: String): ArtifactNodeKind = ArtifactNodeKind.valueOf(value.uppercase())
private fun nodeMapper(json: PostgresJsonSupport): RowMapper<ArtifactNode> = RowMapper { rs, _ -> ArtifactNode(ArtifactNodeId(rs.getString("node_id")), ProjectId(rs.getString("project_id")), rs.getString("iteration_id")?.let(::IterationId), nodeKind(rs.getString("node_kind")), rs.getString("natural_key"), rs.getString("label"), rs.getString("content"), rs.getString("document_id")?.let(::DocumentId), rs.getString("task_id")?.let(::TaskId), rs.getString("run_id")?.let(::RunId), json.metadataFromJson(rs.getString("metadata"))) }
private fun edgeMapper(json: PostgresJsonSupport): RowMapper<ArtifactEdge> = RowMapper { rs, _ -> ArtifactEdge(ArtifactEdgeId(rs.getString("edge_id")), ProjectId(rs.getString("project_id")), ArtifactNodeId(rs.getString("from_node_id")), ArtifactNodeId(rs.getString("to_node_id")), ArtifactEdgeType.valueOf(rs.getString("edge_type")), rs.getString("source_reference"), json.metadataFromJson(rs.getString("metadata"))) }
private fun uuid(value: String): UUID = UUID.fromString(value)
