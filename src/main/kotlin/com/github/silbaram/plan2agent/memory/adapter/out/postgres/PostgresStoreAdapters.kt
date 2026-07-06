package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentSnapshotStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.IterationStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ProjectStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskGraphStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskStorePort
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
import com.github.silbaram.plan2agent.memory.domain.Iteration
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.Project
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskId
import com.github.silbaram.plan2agent.memory.domain.Task
import com.github.silbaram.plan2agent.memory.domain.TaskDependency
import com.github.silbaram.plan2agent.memory.domain.TaskGraph
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.TaskStatus
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class PostgresProjectStoreAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : ProjectStorePort {
    private val json = PostgresJsonSupport(objectMapper)

    override fun save(project: Project): Project = metrics.recordWrite("project.save") {
        val existingProject = findById(project.id) ?: findBySourceProjectId(project.sourceProjectId)
        val projectToSave = existingProject?.let {
            project.copy(id = it.id, canonicalServerId = it.canonicalServerId)
        } ?: project
        val metadata = json.withSourceReference(
            projectToSave.metadata + (PostgresJsonSupport.PROJECT_CANONICAL_SERVER_ID to projectToSave.canonicalServerId.value),
            projectToSave.sourceReference,
        )
        jdbc.queryForObject(
            """
            INSERT INTO projects (
                project_id, source_project_id, name, root_path, metadata, created_at, updated_at
            ) VALUES (
                :projectId, :sourceProjectId, :name, :rootPath, CAST(:metadata AS jsonb), :createdAt, :updatedAt
            )
            ON CONFLICT (project_id) DO UPDATE SET
                source_project_id = EXCLUDED.source_project_id,
                name = EXCLUDED.name,
                root_path = EXCLUDED.root_path,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("projectId", uuid(projectToSave.id.value))
                .addValue("sourceProjectId", projectToSave.sourceProjectId.value)
                .addValue("name", projectToSave.name)
                .addValue("rootPath", projectToSave.rootPath)
                .addValue("metadata", json.metadataToJson(metadata))
                .addValue("createdAt", Timestamp.from(projectToSave.createdAt))
                .addValue("updatedAt", projectToSave.updatedAt?.let(Timestamp::from)),
            projectMapper(json),
        )!!
    }

    override fun findById(id: ProjectId): Project? =
        jdbc.queryOne(
            "SELECT * FROM projects WHERE project_id = :projectId",
            MapSqlParameterSource("projectId", uuid(id.value)),
            projectMapper(json),
        )

    private fun findBySourceProjectId(sourceProjectId: SourceProjectId): Project? =
        jdbc.queryOne(
            "SELECT * FROM projects WHERE source_project_id = :sourceProjectId",
            MapSqlParameterSource("sourceProjectId", sourceProjectId.value),
            projectMapper(json),
        )
}

@Repository
class PostgresIterationStoreAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : IterationStorePort {
    private val json = PostgresJsonSupport(objectMapper)

    override fun save(iteration: Iteration): Iteration = metrics.recordWrite("iteration.save") {
        val existingIteration = findById(iteration.id)
            ?: findByProjectAndSourceIterationId(iteration.projectId, iteration.sourceIterationId)
        val iterationToSave = existingIteration?.let { iteration.copy(id = it.id) } ?: iteration
        val metadata = json.withSourceReference(iterationToSave.metadata, iterationToSave.sourceReference)
        jdbc.queryForObject(
            """
            INSERT INTO iterations (
                iteration_id, source_iteration_id, project_id, label, status, metadata, created_at, updated_at
            ) VALUES (
                :iterationId, :sourceIterationId, :projectId, :label, :status, CAST(:metadata AS jsonb), :createdAt, :updatedAt
            )
            ON CONFLICT (iteration_id) DO UPDATE SET
                source_iteration_id = EXCLUDED.source_iteration_id,
                project_id = EXCLUDED.project_id,
                label = EXCLUDED.label,
                status = EXCLUDED.status,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("iterationId", uuid(iterationToSave.id.value))
                .addValue("sourceIterationId", iterationToSave.sourceIterationId.value)
                .addValue("projectId", uuid(iterationToSave.projectId.value))
                .addValue("label", iterationToSave.label)
                .addValue("status", iterationToSave.status.name)
                .addValue("metadata", json.metadataToJson(metadata))
                .addValue("createdAt", Timestamp.from(iterationToSave.createdAt))
                .addValue("updatedAt", iterationToSave.updatedAt?.let(Timestamp::from)),
            iterationMapper(json),
        )!!
    }

    override fun findById(id: IterationId): Iteration? =
        jdbc.queryOne(
            "SELECT * FROM iterations WHERE iteration_id = :iterationId",
            MapSqlParameterSource("iterationId", uuid(id.value)),
            iterationMapper(json),
        )

    override fun findByProjectId(projectId: ProjectId): List<Iteration> =
        jdbc.query(
            "SELECT * FROM iterations WHERE project_id = :projectId ORDER BY created_at, iteration_id",
            MapSqlParameterSource("projectId", uuid(projectId.value)),
            iterationMapper(json),
        )

    private fun findByProjectAndSourceIterationId(
        projectId: ProjectId,
        sourceIterationId: SourceIterationId,
    ): Iteration? =
        jdbc.queryOne(
            """
            SELECT *
            FROM iterations
            WHERE project_id = :projectId
              AND source_iteration_id = :sourceIterationId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("projectId", uuid(projectId.value))
                .addValue("sourceIterationId", sourceIterationId.value),
            iterationMapper(json),
        )
}

@Repository
class PostgresDocumentSnapshotStoreAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : DocumentSnapshotStorePort {
    private val json = PostgresJsonSupport(objectMapper)

    override fun save(documentSnapshot: DocumentSnapshot): DocumentSnapshot = metrics.recordWrite("document_snapshot.save") {
        findExistingLogicalSnapshot(documentSnapshot)?.let { return@recordWrite it }

        val metadata = json.withSourceReference(
            documentSnapshot.metadata + mapOf(
                PostgresJsonSupport.DOCUMENT_TITLE to documentSnapshot.title,
                PostgresJsonSupport.DOCUMENT_CAPTURED_AT to documentSnapshot.capturedAt.toString(),
            ),
            documentSnapshot.sourceReference,
        )

        jdbc.queryForObject(
            """
            WITH next_snapshot AS (
                SELECT COALESCE(MAX(snapshot_version), 0) + 1 AS snapshot_version
                FROM documents
                WHERE project_id = :projectId
                  AND iteration_id IS NOT DISTINCT FROM :iterationId
                  AND artifact_type = :artifactType
                  AND source_path = :sourcePath
            )
            INSERT INTO documents (
                document_id, source_document_id, project_id, iteration_id, artifact_type, source_path,
                raw_source_path, content_hash, snapshot_version, content, metadata, created_at, updated_at
            )
            SELECT
                :documentId, :sourceDocumentId, :projectId, :iterationId, :artifactType, :sourcePath,
                :rawSourcePath, :contentHash, next_snapshot.snapshot_version, :content,
                CAST(:metadata AS jsonb), :createdAt, :updatedAt
            FROM next_snapshot
            ON CONFLICT ON CONSTRAINT uq_documents_logical_snapshot_hash DO UPDATE SET
                updated_at = documents.updated_at
            RETURNING *
            """.trimIndent(),
            documentSnapshotParams(documentSnapshot, metadata),
            documentSnapshotMapper(json),
        )!!
    }

    override fun findById(id: DocumentId): DocumentSnapshot? =
        jdbc.queryOne(
            "SELECT * FROM documents WHERE document_id = :documentId",
            MapSqlParameterSource("documentId", uuid(id.value)),
            documentSnapshotMapper(json),
        )

    override fun findByIterationId(iterationId: IterationId): List<DocumentSnapshot> =
        jdbc.query(
            "SELECT * FROM documents WHERE iteration_id = :iterationId ORDER BY source_path, snapshot_version",
            MapSqlParameterSource("iterationId", uuid(iterationId.value)),
            documentSnapshotMapper(json),
        )

    private fun findExistingLogicalSnapshot(documentSnapshot: DocumentSnapshot): DocumentSnapshot? =
        jdbc.queryOne(
            """
            SELECT *
            FROM documents
            WHERE project_id = :projectId
              AND iteration_id IS NOT DISTINCT FROM :iterationId
              AND artifact_type = :artifactType
              AND source_path = :sourcePath
              AND content_hash = :contentHash
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("projectId", uuid(documentSnapshot.projectId.value))
                .addValue("iterationId", documentSnapshot.iterationId?.value?.let(::uuid))
                .addValue("artifactType", documentSnapshot.artifactType.name)
                .addValue("sourcePath", documentSnapshot.sourcePath)
                .addValue("contentHash", documentSnapshot.contentHash.value),
            documentSnapshotMapper(json),
        )

    private fun documentSnapshotParams(
        documentSnapshot: DocumentSnapshot,
        metadata: Map<String, String>,
    ): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("documentId", uuid(documentSnapshot.id.value))
            .addValue("sourceDocumentId", documentSnapshot.sourceDocumentId.value)
            .addValue("projectId", uuid(documentSnapshot.projectId.value))
            .addValue("iterationId", documentSnapshot.iterationId?.value?.let(::uuid))
            .addValue("artifactType", documentSnapshot.artifactType.name)
            .addValue("sourcePath", documentSnapshot.sourcePath)
            .addValue("rawSourcePath", documentSnapshot.sourceReference?.path)
            .addValue("contentHash", documentSnapshot.contentHash.value)
            .addValue("content", documentSnapshot.content)
            .addValue("metadata", json.metadataToJson(metadata))
            .addValue("createdAt", Timestamp.from(documentSnapshot.createdAt))
            .addValue("updatedAt", documentSnapshot.updatedAt?.let(Timestamp::from))
}

@Repository
class PostgresTaskGraphStoreAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : TaskGraphStorePort {
    private val json = PostgresJsonSupport(objectMapper)

    override fun save(taskGraph: TaskGraph): TaskGraph = metrics.recordWrite("task_graph.save") {
        val existingTaskGraph = findById(taskGraph.id)
            ?: findByProjectIterationAndSourceTaskGraphId(
                taskGraph.projectId,
                taskGraph.iterationId,
                taskGraph.sourceTaskGraphId,
            )
            ?: findByProjectIterationAndGraphHash(taskGraph.projectId, taskGraph.iterationId, taskGraph.graphHash)
        val taskGraphToSave = existingTaskGraph?.let { taskGraph.copy(id = it.id) } ?: taskGraph
        val metadata = json.withSourceReference(
            taskGraphToSave.metadata + mapOf(
                PostgresJsonSupport.TASK_GRAPH_TASK_IDS to json.stringsToJson(taskGraphToSave.taskIds.map { it.value }),
                PostgresJsonSupport.TASK_GRAPH_DEPENDENCY_EDGES to json.dependencyEdgesToJson(
                    taskGraphToSave.dependencyEdges.map {
                        DependencyEdgeJson(
                            fromTaskId = it.fromTaskId.value,
                            toTaskId = it.toTaskId.value,
                        )
                    },
                ),
            ),
            taskGraphToSave.sourceReference,
        )
        val documentId = resolveDocumentId(taskGraphToSave)

        jdbc.queryForObject(
            """
            INSERT INTO task_graphs (
                task_graph_id, source_task_graph_id, project_id, iteration_id, document_id,
                source_document_id, graph_hash, graph_json, metadata, created_at, updated_at
            ) VALUES (
                :taskGraphId, :sourceTaskGraphId, :projectId, :iterationId, :documentId,
                :sourceDocumentId, :graphHash, CAST(:graphJson AS jsonb), CAST(:metadata AS jsonb),
                :createdAt, :updatedAt
            )
            ON CONFLICT (task_graph_id) DO UPDATE SET
                source_task_graph_id = EXCLUDED.source_task_graph_id,
                project_id = EXCLUDED.project_id,
                iteration_id = EXCLUDED.iteration_id,
                document_id = EXCLUDED.document_id,
                source_document_id = EXCLUDED.source_document_id,
                graph_hash = EXCLUDED.graph_hash,
                graph_json = EXCLUDED.graph_json,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("taskGraphId", uuid(taskGraphToSave.id.value))
                .addValue("sourceTaskGraphId", taskGraphToSave.sourceTaskGraphId.value)
                .addValue("projectId", uuid(taskGraphToSave.projectId.value))
                .addValue("iterationId", uuid(taskGraphToSave.iterationId.value))
                .addValue("documentId", documentId?.let(::uuid))
                .addValue("sourceDocumentId", taskGraphToSave.sourceDocumentId?.value)
                .addValue("graphHash", taskGraphToSave.graphHash.value)
                .addValue("graphJson", taskGraphToSave.graphJson)
                .addValue("metadata", json.metadataToJson(metadata))
                .addValue("createdAt", Timestamp.from(taskGraphToSave.createdAt))
                .addValue("updatedAt", taskGraphToSave.updatedAt?.let(Timestamp::from)),
            taskGraphMapper(json),
        )!!
    }

    override fun findById(id: TaskGraphId): TaskGraph? =
        jdbc.queryOne(
            "SELECT * FROM task_graphs WHERE task_graph_id = :taskGraphId",
            MapSqlParameterSource("taskGraphId", uuid(id.value)),
            taskGraphMapper(json),
        )

    override fun findByIterationId(iterationId: IterationId): List<TaskGraph> =
        jdbc.query(
            "SELECT * FROM task_graphs WHERE iteration_id = :iterationId ORDER BY created_at, task_graph_id",
            MapSqlParameterSource("iterationId", uuid(iterationId.value)),
            taskGraphMapper(json),
        )

    private fun findByProjectIterationAndSourceTaskGraphId(
        projectId: ProjectId,
        iterationId: IterationId,
        sourceTaskGraphId: SourceTaskGraphId,
    ): TaskGraph? =
        jdbc.queryOne(
            """
            SELECT *
            FROM task_graphs
            WHERE project_id = :projectId
              AND iteration_id = :iterationId
              AND source_task_graph_id = :sourceTaskGraphId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("projectId", uuid(projectId.value))
                .addValue("iterationId", uuid(iterationId.value))
                .addValue("sourceTaskGraphId", sourceTaskGraphId.value),
            taskGraphMapper(json),
        )

    private fun findByProjectIterationAndGraphHash(
        projectId: ProjectId,
        iterationId: IterationId,
        graphHash: ContentHash,
    ): TaskGraph? =
        jdbc.queryOne(
            """
            SELECT *
            FROM task_graphs
            WHERE project_id = :projectId
              AND iteration_id = :iterationId
              AND graph_hash = :graphHash
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("projectId", uuid(projectId.value))
                .addValue("iterationId", uuid(iterationId.value))
                .addValue("graphHash", graphHash.value),
            taskGraphMapper(json),
        )

    private fun resolveDocumentId(taskGraph: TaskGraph): String? =
        taskGraph.sourceDocumentId?.let { sourceDocumentId ->
            jdbc.queryOne(
                """
                SELECT document_id::text
                FROM documents
                WHERE project_id = :projectId
                  AND iteration_id = :iterationId
                  AND source_document_id = :sourceDocumentId
                ORDER BY snapshot_version DESC, created_at DESC, document_id
                LIMIT 1
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("projectId", uuid(taskGraph.projectId.value))
                    .addValue("iterationId", uuid(taskGraph.iterationId.value))
                    .addValue("sourceDocumentId", sourceDocumentId.value),
            ) { rs, _ -> rs.getString("document_id") }
                ?: error(
                    "Document source id ${sourceDocumentId.value} was not found in iteration ${taskGraph.iterationId.value}",
                )
        }
}

@Repository
class PostgresTaskStoreAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : TaskStorePort {
    private val json = PostgresJsonSupport(objectMapper)

    override fun saveAll(tasks: List<Task>): List<Task> = metrics.recordWrite("task.save_all") {
        if (tasks.isEmpty()) {
            return@recordWrite emptyList()
        }
        val tasksToSave = tasks.map { task ->
            val existingTask = findById(task.id)
                ?: findByGraphAndSourceTaskId(task.taskGraphId, task.sourceTaskId)
                ?: findByProjectIterationAndSourceTaskId(task.projectId, task.iterationId, task.sourceTaskId)
            existingTask?.let { task.copy(id = it.id) } ?: task
        }
        jdbc.batchUpdate(
            """
            INSERT INTO tasks (
                task_id, source_task_id, project_id, iteration_id, task_graph_id, title,
                description, status, target_area, dependencies_json, acceptance_criteria_json,
                metadata, created_at, updated_at
            ) VALUES (
                :taskId, :sourceTaskId, :projectId, :iterationId, :taskGraphId, :title,
                :description, :status, :targetArea, CAST(:dependenciesJson AS jsonb),
                CAST(:acceptanceCriteriaJson AS jsonb), CAST(:metadata AS jsonb),
                :createdAt, :updatedAt
            )
            ON CONFLICT (task_id) DO UPDATE SET
                source_task_id = EXCLUDED.source_task_id,
                project_id = EXCLUDED.project_id,
                iteration_id = EXCLUDED.iteration_id,
                task_graph_id = EXCLUDED.task_graph_id,
                title = EXCLUDED.title,
                description = EXCLUDED.description,
                status = EXCLUDED.status,
                target_area = EXCLUDED.target_area,
                dependencies_json = EXCLUDED.dependencies_json,
                acceptance_criteria_json = EXCLUDED.acceptance_criteria_json,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            tasksToSave.map(::taskParams).toTypedArray(),
        )
        val savedById = findByIds(tasksToSave.map { it.id }).associateBy { it.id }
        tasksToSave.map { task -> savedById.getValue(task.id) }
    }

    override fun findById(id: TaskId): Task? =
        jdbc.queryOne(
            "SELECT * FROM tasks WHERE task_id = :taskId",
            MapSqlParameterSource("taskId", uuid(id.value)),
            taskMapper(json),
        )

    override fun findByGraphId(graphId: TaskGraphId): List<Task> =
        jdbc.query(
            "SELECT * FROM tasks WHERE task_graph_id = :taskGraphId ORDER BY created_at, task_id",
            MapSqlParameterSource("taskGraphId", uuid(graphId.value)),
            taskMapper(json),
        )

    private fun findByIds(ids: List<TaskId>): List<Task> =
        jdbc.query(
            """
            SELECT *
            FROM tasks
            WHERE task_id IN (:taskIds)
            """.trimIndent(),
            MapSqlParameterSource("taskIds", ids.map { uuid(it.value) }),
            taskMapper(json),
        )

    private fun taskParams(task: Task): MapSqlParameterSource {
        val metadata = json.withSourceReference(task.metadata, task.sourceReference)
        return MapSqlParameterSource()
            .addValue("taskId", uuid(task.id.value))
            .addValue("sourceTaskId", task.sourceTaskId.value)
            .addValue("projectId", uuid(task.projectId.value))
            .addValue("iterationId", uuid(task.iterationId.value))
            .addValue("taskGraphId", uuid(task.taskGraphId.value))
            .addValue("title", task.title)
            .addValue("description", task.description)
            .addValue("status", task.status.name)
            .addValue("targetArea", task.targetArea)
            .addValue("dependenciesJson", json.stringsToJson(task.dependencies.map { it.value }))
            .addValue("acceptanceCriteriaJson", json.stringsToJson(task.acceptanceCriteria))
            .addValue("metadata", json.metadataToJson(metadata))
            .addValue("createdAt", Timestamp.from(task.createdAt))
            .addValue("updatedAt", task.updatedAt?.let(Timestamp::from))
    }

    private fun findByGraphAndSourceTaskId(graphId: TaskGraphId, sourceTaskId: SourceTaskId): Task? =
        jdbc.queryOne(
            """
            SELECT *
            FROM tasks
            WHERE task_graph_id = :taskGraphId
              AND source_task_id = :sourceTaskId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("taskGraphId", uuid(graphId.value))
                .addValue("sourceTaskId", sourceTaskId.value),
            taskMapper(json),
        )

    private fun findByProjectIterationAndSourceTaskId(
        projectId: ProjectId,
        iterationId: IterationId,
        sourceTaskId: SourceTaskId,
    ): Task? =
        jdbc.queryOne(
            """
            SELECT *
            FROM tasks
            WHERE project_id = :projectId
              AND iteration_id = :iterationId
              AND source_task_id = :sourceTaskId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("projectId", uuid(projectId.value))
                .addValue("iterationId", uuid(iterationId.value))
                .addValue("sourceTaskId", sourceTaskId.value),
            taskMapper(json),
        )
}

private fun uuid(value: String): UUID =
    UUID.fromString(value)

private fun projectMapper(json: PostgresJsonSupport): RowMapper<Project> =
    RowMapper { rs, _ ->
        val metadata = json.metadataFromJson(rs.getString("metadata"))
        val projectId = rs.getString("project_id")
        Project(
            id = ProjectId(projectId),
            sourceProjectId = SourceProjectId(rs.getString("source_project_id")),
            name = rs.getString("name"),
            canonicalServerId = CanonicalServerId(metadata[PostgresJsonSupport.PROJECT_CANONICAL_SERVER_ID] ?: projectId),
            rootPath = rs.getString("root_path"),
            sourceReference = json.sourceReferenceFrom(metadata),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.nullableInstant("updated_at"),
            metadata = json.withoutReservedMetadata(metadata),
        )
    }

private fun iterationMapper(json: PostgresJsonSupport): RowMapper<Iteration> =
    RowMapper { rs, _ ->
        val metadata = json.metadataFromJson(rs.getString("metadata"))
        Iteration(
            id = IterationId(rs.getString("iteration_id")),
            projectId = ProjectId(rs.getString("project_id")),
            sourceIterationId = SourceIterationId(rs.getString("source_iteration_id")),
            label = rs.getString("label"),
            status = IterationStatus.valueOf(rs.getString("status")),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.nullableInstant("updated_at"),
            sourceReference = json.sourceReferenceFrom(metadata),
            metadata = json.withoutReservedMetadata(metadata),
        )
    }

private fun documentSnapshotMapper(json: PostgresJsonSupport): RowMapper<DocumentSnapshot> =
    RowMapper { rs, _ ->
        val metadata = json.metadataFromJson(rs.getString("metadata"))
        val sourcePath = rs.getString("source_path")
        DocumentSnapshot(
            id = DocumentId(rs.getString("document_id")),
            projectId = ProjectId(rs.getString("project_id")),
            iterationId = rs.getString("iteration_id")?.let(::IterationId),
            sourceDocumentId = SourceDocumentId(rs.getString("source_document_id")),
            sourcePath = sourcePath,
            snapshotVersion = rs.getInt("snapshot_version"),
            artifactType = ArtifactType.valueOf(rs.getString("artifact_type")),
            title = metadata[PostgresJsonSupport.DOCUMENT_TITLE] ?: sourcePath,
            content = rs.getString("content"),
            contentHash = ContentHash(rs.getString("content_hash")),
            sourceReference = json.sourceReferenceFrom(metadata),
            capturedAt = metadata[PostgresJsonSupport.DOCUMENT_CAPTURED_AT]?.let(Instant::parse)
                ?: rs.instant("created_at"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.nullableInstant("updated_at"),
            metadata = json.withoutReservedMetadata(metadata),
        )
    }

private fun taskGraphMapper(json: PostgresJsonSupport): RowMapper<TaskGraph> =
    RowMapper { rs, _ ->
        val metadata = json.metadataFromJson(rs.getString("metadata"))
        val taskIds = json.stringsFromJson(metadata[PostgresJsonSupport.TASK_GRAPH_TASK_IDS])
            .map(::TaskId)
            .toSet()
        val dependencyEdges = json.dependencyEdgesFromJson(metadata[PostgresJsonSupport.TASK_GRAPH_DEPENDENCY_EDGES])
            .map { TaskDependency(TaskId(it.fromTaskId), TaskId(it.toTaskId)) }
            .toSet()
        TaskGraph(
            id = TaskGraphId(rs.getString("task_graph_id")),
            projectId = ProjectId(rs.getString("project_id")),
            iterationId = IterationId(rs.getString("iteration_id")),
            sourceTaskGraphId = SourceTaskGraphId(rs.getString("source_task_graph_id")),
            sourceDocumentId = rs.getString("source_document_id")?.let(::SourceDocumentId),
            graphHash = ContentHash(rs.getString("graph_hash")),
            graphJson = rs.getString("graph_json"),
            taskIds = taskIds,
            dependencyEdges = dependencyEdges,
            sourceReference = json.sourceReferenceFrom(metadata),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.nullableInstant("updated_at"),
            metadata = json.withoutReservedMetadata(metadata),
        )
    }

private fun taskMapper(json: PostgresJsonSupport): RowMapper<Task> =
    RowMapper { rs, _ ->
        val metadata = json.metadataFromJson(rs.getString("metadata"))
        Task(
            id = TaskId(rs.getString("task_id")),
            projectId = ProjectId(rs.getString("project_id")),
            iterationId = IterationId(rs.getString("iteration_id")),
            taskGraphId = TaskGraphId(rs.getString("task_graph_id")),
            sourceTaskId = SourceTaskId(rs.getString("source_task_id")),
            title = rs.getString("title"),
            description = rs.getString("description"),
            status = TaskStatus.valueOf(rs.getString("status")),
            targetArea = rs.getString("target_area"),
            dependencies = json.stringsFromJson(rs.getString("dependencies_json")).map(::TaskId).toSet(),
            acceptanceCriteria = json.stringsFromJson(rs.getString("acceptance_criteria_json")),
            sourceReference = json.sourceReferenceFrom(metadata),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.nullableInstant("updated_at"),
            metadata = json.withoutReservedMetadata(metadata),
        )
    }

private fun <T> NamedParameterJdbcTemplate.queryOne(
    sql: String,
    params: MapSqlParameterSource,
    mapper: RowMapper<T>,
): T? =
    try {
        queryForObject(sql, params, mapper)
    } catch (_: EmptyResultDataAccessException) {
        null
    }

private fun ResultSet.instant(column: String): Instant =
    getTimestamp(column).toInstant()

private fun ResultSet.nullableInstant(column: String): Instant? =
    getTimestamp(column)?.toInstant()
