package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.silbaram.plan2agent.memory.application.port.out.ChunkEmbeddingStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentChunkStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingSetStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.RunRecordStorePort
import com.github.silbaram.plan2agent.memory.domain.ArtifactRef
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.ChunkEmbedding
import com.github.silbaram.plan2agent.memory.domain.ChunkEmbeddingId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSet
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingStorageType
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.RunRecord
import com.github.silbaram.plan2agent.memory.domain.RunStatus
import com.github.silbaram.plan2agent.memory.domain.SourceRunId
import com.github.silbaram.plan2agent.memory.domain.TaskId
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
class PostgresRunRecordStoreAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : RunRecordStorePort {
    private val json = PostgresJsonSupport(objectMapper)

    override fun save(runRecord: RunRecord): RunRecord = metrics.recordWrite("run.save") {
        findById(runRecord.id)?.let { existingRun ->
            existingRun.requireSameSourceScope(runRecord)
            return@recordWrite upsert(runRecord.copy(id = existingRun.id))
        }
        findByProjectIterationAndSourceRunId(
            runRecord.projectId,
            runRecord.iterationId,
            runRecord.sourceRunId,
        )?.let { existingRun ->
            throw relationConflict(
                "run source id ${runRecord.sourceRunId.value} already maps to canonical run ${existingRun.id.value}",
            )
        }
        upsert(runRecord)
    }

    private fun upsert(runToSave: RunRecord): RunRecord {
        val metadata = json.withSourceReference(runToSave.metadata, runToSave.sourceReference)
        val artifactRefsJson = json.artifactRefsToJson(
            runToSave.artifactRefs.map {
                ArtifactRefJson(
                    artifactType = it.artifactType.name,
                    artifactId = it.artifactId,
                    sourcePath = it.sourcePath,
                )
            },
        )

        return jdbc.queryForObject(
            """
            INSERT INTO runs (
                run_id, source_run_id, project_id, iteration_id, task_id, status, agent_tool,
                started_at, finished_at, run_json, artifact_refs_json, metadata, created_at, updated_at
            ) VALUES (
                :runId, :sourceRunId, :projectId, :iterationId, :taskId, :status, :agentTool,
                :startedAt, :finishedAt, CAST(:runJson AS jsonb), CAST(:artifactRefsJson AS jsonb),
                CAST(:metadata AS jsonb), :createdAt, :updatedAt
            )
            ON CONFLICT (run_id) DO UPDATE SET
                source_run_id = EXCLUDED.source_run_id,
                project_id = EXCLUDED.project_id,
                iteration_id = EXCLUDED.iteration_id,
                task_id = EXCLUDED.task_id,
                status = EXCLUDED.status,
                agent_tool = EXCLUDED.agent_tool,
                started_at = EXCLUDED.started_at,
                finished_at = EXCLUDED.finished_at,
                run_json = EXCLUDED.run_json,
                artifact_refs_json = EXCLUDED.artifact_refs_json,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("runId", uuid(runToSave.id.value))
                .addValue("sourceRunId", runToSave.sourceRunId.value)
                .addValue("projectId", uuid(runToSave.projectId.value))
                .addValue("iterationId", uuid(runToSave.iterationId.value))
                .addValue("taskId", uuid(runToSave.taskId.value))
                .addValue("status", runToSave.status.name)
                .addValue("agentTool", runToSave.agentTool)
                .addValue("startedAt", Timestamp.from(runToSave.startedAt))
                .addValue("finishedAt", runToSave.finishedAt?.let(Timestamp::from))
                .addValue("runJson", runToSave.runJson)
                .addValue("artifactRefsJson", artifactRefsJson)
                .addValue("metadata", json.metadataToJson(metadata))
                .addValue("createdAt", Timestamp.from(runToSave.createdAt))
                .addValue("updatedAt", runToSave.updatedAt?.let(Timestamp::from)),
            runRecordMapper(json),
        )!!
    }

    override fun findById(id: RunId): RunRecord? =
        jdbc.queryOne(
            "SELECT * FROM runs WHERE run_id = :runId",
            MapSqlParameterSource("runId", uuid(id.value)),
            runRecordMapper(json),
        )

    override fun findByTaskId(taskId: TaskId): List<RunRecord> =
        jdbc.query(
            "SELECT * FROM runs WHERE task_id = :taskId ORDER BY started_at, run_id",
            MapSqlParameterSource("taskId", uuid(taskId.value)),
            runRecordMapper(json),
        )

    private fun findByProjectIterationAndSourceRunId(
        projectId: ProjectId,
        iterationId: IterationId,
        sourceRunId: SourceRunId,
    ): RunRecord? =
        jdbc.queryOne(
            """
            SELECT *
            FROM runs
            WHERE project_id = :projectId
              AND iteration_id = :iterationId
              AND source_run_id = :sourceRunId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("projectId", uuid(projectId.value))
                .addValue("iterationId", uuid(iterationId.value))
                .addValue("sourceRunId", sourceRunId.value),
            runRecordMapper(json),
        )
}

@Repository
class PostgresDocumentChunkStoreAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : DocumentChunkStorePort {
    private val json = PostgresJsonSupport(objectMapper)

    override fun saveAll(chunks: List<DocumentChunk>): List<DocumentChunk> = metrics.recordWrite("document_chunk.save_all") {
        if (chunks.isEmpty()) {
            return@recordWrite emptyList()
        }
        val existingByInputId = mutableMapOf<DocumentChunkId, DocumentChunk>()
        val chunksToInsert = mutableListOf<DocumentChunk>()
        chunks.forEach { chunk ->
            val existingByHash = findByDocumentIdAndChunkHash(chunk.documentId, chunk.chunkHash)
            if (existingByHash != null) {
                existingByInputId[chunk.id] = existingByHash
            } else {
                val existingById = findById(chunk.id)
                if (existingById != null) {
                    if (existingById.documentId != chunk.documentId || existingById.chunkHash != chunk.chunkHash) {
                        throw relationConflict(
                            "document chunk ${chunk.id.value} already maps to document ${existingById.documentId.value} " +
                                "and hash ${existingById.chunkHash.value}",
                        )
                    }
                    existingByInputId[chunk.id] = existingById
                } else {
                    chunksToInsert += chunk
                }
            }
        }
        if (chunksToInsert.isNotEmpty()) {
            jdbc.batchUpdate(
                """
                INSERT INTO document_chunks (
                    chunk_id, source_chunk_id, document_id, project_id, iteration_id, task_id, run_id,
                    artifact_type, source_path, raw_source_path, chunk_index, chunk_hash, content,
                    token_estimate, metadata, created_at, updated_at
                ) VALUES (
                    :chunkId, NULL, :documentId, :projectId, :iterationId, :taskId, :runId,
                    :artifactType, :sourcePath, :rawSourcePath, :chunkIndex, :chunkHash, :content,
                    :tokenEstimate, CAST(:metadata AS jsonb), :createdAt, NULL
                )
                """.trimIndent(),
                chunksToInsert.map(::documentChunkParams).toTypedArray(),
            )
        }
        val insertedById = findByIds(chunksToInsert.map { it.id }).associateBy { it.id }
        chunks.map { chunk -> existingByInputId[chunk.id] ?: insertedById.getValue(chunk.id) }
    }

    override fun findByDocumentId(documentId: DocumentId): List<DocumentChunk> =
        jdbc.query(
            "SELECT * FROM document_chunks WHERE document_id = :documentId ORDER BY chunk_index, chunk_id",
            MapSqlParameterSource("documentId", uuid(documentId.value)),
            documentChunkMapper(json),
        )

    private fun documentChunkParams(chunk: DocumentChunk): MapSqlParameterSource {
        val metadata = json.withSourceReference(chunk.metadata, chunk.sourceReference)
        return MapSqlParameterSource()
            .addValue("chunkId", uuid(chunk.id.value))
            .addValue("documentId", uuid(chunk.documentId.value))
            .addValue("projectId", uuid(chunk.projectId.value))
            .addValue("iterationId", chunk.iterationId?.value?.let(::uuid))
            .addValue("taskId", chunk.taskId?.value?.let(::uuid))
            .addValue("runId", chunk.runId?.value?.let(::uuid))
            .addValue("artifactType", chunk.artifactType.name)
            .addValue("sourcePath", chunk.sourcePath)
            .addValue("rawSourcePath", chunk.sourceReference?.path)
            .addValue("chunkIndex", chunk.chunkIndex)
            .addValue("chunkHash", chunk.chunkHash.value)
            .addValue("content", chunk.content)
            .addValue("tokenEstimate", chunk.tokenEstimate)
            .addValue("metadata", json.metadataToJson(metadata))
            .addValue("createdAt", Timestamp.from(chunk.createdAt))
    }

    private fun findById(id: DocumentChunkId): DocumentChunk? =
        jdbc.queryOne(
            "SELECT * FROM document_chunks WHERE chunk_id = :chunkId",
            MapSqlParameterSource("chunkId", uuid(id.value)),
            documentChunkMapper(json),
        )

    private fun findByDocumentIdAndChunkHash(documentId: DocumentId, chunkHash: ContentHash): DocumentChunk? =
        jdbc.queryOne(
            """
            SELECT *
            FROM document_chunks
            WHERE document_id = :documentId
              AND chunk_hash = :chunkHash
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("documentId", uuid(documentId.value))
                .addValue("chunkHash", chunkHash.value),
            documentChunkMapper(json),
        )

    private fun findByIds(ids: List<DocumentChunkId>): List<DocumentChunk> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc.query(
                """
                SELECT *
                FROM document_chunks
                WHERE chunk_id IN (:chunkIds)
                """.trimIndent(),
                MapSqlParameterSource("chunkIds", ids.map { uuid(it.value) }),
                documentChunkMapper(json),
            )
        }
}

@Repository
class PostgresEmbeddingSetStoreAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : EmbeddingSetStorePort {
    private val json = PostgresJsonSupport(objectMapper)

    override fun resolveOrCreate(embeddingSet: EmbeddingSet): EmbeddingSet = metrics.recordWrite("embedding_set.resolve_or_create") {
        require(embeddingSet.embeddingDimension <= MAX_VECTOR_DIMENSION) {
            "EmbeddingSet embeddingDimension must be <= $MAX_VECTOR_DIMENSION for pgvector storage"
        }

        findByUniqueKey(
            embeddingSet.embeddingModel,
            embeddingSet.embeddingDimension,
            embeddingSet.embeddingVersion,
            embeddingSet.distanceMetric,
        )?.let { return@recordWrite it }

        findById(embeddingSet.id)?.let { existing ->
            if (existing.uniqueKey() != embeddingSet.uniqueKey()) {
                throw relationConflict(
                    "embedding set ${embeddingSet.id.value} already maps to ${existing.uniqueKey()}",
                )
            }
            return@recordWrite existing
        }

        jdbc.queryForObject(
            """
            INSERT INTO embedding_sets (
                embedding_set_id, project_id, embedding_model, embedding_dimension,
                embedding_version, distance_metric, storage_type, metadata, created_at, updated_at
            ) VALUES (
                :embeddingSetId, :projectId, :embeddingModel, :embeddingDimension,
                :embeddingVersion, :distanceMetric, :storageType, CAST(:metadata AS jsonb),
                :createdAt, NULL
            )
            ON CONFLICT ON CONSTRAINT uq_embedding_sets_model_dimension_version_metric DO UPDATE SET
                updated_at = embedding_sets.updated_at
            RETURNING *
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("embeddingSetId", uuid(embeddingSet.id.value))
                .addValue("projectId", uuid(embeddingSet.projectId.value))
                .addValue("embeddingModel", embeddingSet.embeddingModel)
                .addValue("embeddingDimension", embeddingSet.embeddingDimension)
                .addValue("embeddingVersion", embeddingSet.embeddingVersion)
                .addValue("distanceMetric", embeddingSet.distanceMetric.toDbValue())
                .addValue("storageType", embeddingSet.storageType.toDbValue())
                .addValue("metadata", json.metadataToJson(embeddingSet.metadata))
                .addValue("createdAt", Timestamp.from(embeddingSet.createdAt)),
            embeddingSetMapper(json),
        )!!
    }

    override fun findById(id: EmbeddingSetId): EmbeddingSet? =
        jdbc.queryOne(
            "SELECT * FROM embedding_sets WHERE embedding_set_id = :embeddingSetId",
            MapSqlParameterSource("embeddingSetId", uuid(id.value)),
            embeddingSetMapper(json),
        )

    override fun findByUniqueKey(
        embeddingModel: String,
        embeddingDimension: Int,
        embeddingVersion: String,
        distanceMetric: DistanceMetric,
    ): EmbeddingSet? =
        jdbc.queryOne(
            """
            SELECT *
            FROM embedding_sets
            WHERE embedding_model = :embeddingModel
              AND embedding_dimension = :embeddingDimension
              AND embedding_version = :embeddingVersion
              AND distance_metric = :distanceMetric
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("embeddingModel", embeddingModel)
                .addValue("embeddingDimension", embeddingDimension)
                .addValue("embeddingVersion", embeddingVersion)
                .addValue("distanceMetric", distanceMetric.toDbValue()),
            embeddingSetMapper(json),
        )
}

@Repository
class PostgresChunkEmbeddingStoreAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    objectMapper: ObjectMapper,
) : ChunkEmbeddingStorePort {
    private val json = PostgresJsonSupport(objectMapper)

    override fun saveAll(chunkEmbeddings: List<ChunkEmbedding>): List<ChunkEmbedding> =
        metrics.recordWrite("chunk_embedding.save_all") {
            if (chunkEmbeddings.isEmpty()) {
                return@recordWrite emptyList()
            }
            val existingByInputId = mutableMapOf<ChunkEmbeddingId, ChunkEmbedding>()
            val embeddingsToInsert = mutableListOf<ChunkEmbedding>()
            chunkEmbeddings.forEach { chunkEmbedding ->
                val existingByChunkAndSet = findByChunkAndEmbeddingSet(
                    chunkEmbedding.chunkId,
                    chunkEmbedding.embeddingSetId,
                )
                if (existingByChunkAndSet != null) {
                    existingByInputId[chunkEmbedding.id] = requireIdempotentEmbedding(existingByChunkAndSet, chunkEmbedding)
                } else {
                    val existingById = findById(chunkEmbedding.id)
                    if (existingById != null) {
                        if (
                            existingById.chunkId != chunkEmbedding.chunkId ||
                            existingById.embeddingSetId != chunkEmbedding.embeddingSetId
                        ) {
                            throw relationConflict(
                                "chunk embedding ${chunkEmbedding.id.value} already maps to chunk " +
                                    "${existingById.chunkId.value} and embedding set ${existingById.embeddingSetId.value}",
                            )
                        }
                        existingByInputId[chunkEmbedding.id] = requireIdempotentEmbedding(existingById, chunkEmbedding)
                    } else {
                        embeddingsToInsert += chunkEmbedding
                    }
                }
            }
            if (embeddingsToInsert.isNotEmpty()) {
                jdbc.batchUpdate(
                    """
                    INSERT INTO chunk_embeddings (
                        chunk_embedding_id, chunk_id, embedding_set_id, embedding, embedding_hash,
                        metadata, created_at, updated_at
                    ) VALUES (
                        :chunkEmbeddingId, :chunkId, :embeddingSetId, CAST(:embedding AS vector), :embeddingHash,
                        CAST(:metadata AS jsonb), :createdAt, NULL
                    )
                    """.trimIndent(),
                    embeddingsToInsert.map(::chunkEmbeddingParams).toTypedArray(),
                )
                saveTypedVectorCopies(embeddingsToInsert)
            }
            val insertedById = findByIds(embeddingsToInsert.map { it.id }).associateBy { it.id }
            chunkEmbeddings.map { chunkEmbedding ->
                existingByInputId[chunkEmbedding.id] ?: insertedById.getValue(chunkEmbedding.id)
            }
        }

    override fun findByChunkId(chunkId: DocumentChunkId): List<ChunkEmbedding> =
        jdbc.query(
            """
            SELECT *
            FROM chunk_embeddings
            WHERE chunk_id = :chunkId
            ORDER BY embedding_set_id, chunk_embedding_id
            """.trimIndent(),
            MapSqlParameterSource("chunkId", uuid(chunkId.value)),
            chunkEmbeddingMapper(json),
        )

    private fun chunkEmbeddingParams(chunkEmbedding: ChunkEmbedding): MapSqlParameterSource {
        val metadata = json.metadataToJson(chunkEmbedding.metadata)
        return MapSqlParameterSource()
            .addValue("chunkEmbeddingId", uuid(chunkEmbedding.id.value))
            .addValue("chunkId", uuid(chunkEmbedding.chunkId.value))
            .addValue("embeddingSetId", uuid(chunkEmbedding.embeddingSetId.value))
            .addValue("embedding", chunkEmbedding.embedding.toPgVectorLiteral())
            .addValue("embeddingHash", chunkEmbedding.embeddingHash?.value)
            .addValue("metadata", metadata)
            .addValue("createdAt", Timestamp.from(chunkEmbedding.createdAt))
    }

    private fun findById(id: ChunkEmbeddingId): ChunkEmbedding? =
        jdbc.queryOne(
            "SELECT * FROM chunk_embeddings WHERE chunk_embedding_id = :chunkEmbeddingId",
            MapSqlParameterSource("chunkEmbeddingId", uuid(id.value)),
            chunkEmbeddingMapper(json),
        )

    private fun findByChunkAndEmbeddingSet(
        chunkId: DocumentChunkId,
        embeddingSetId: EmbeddingSetId,
    ): ChunkEmbedding? =
        jdbc.queryOne(
            """
            SELECT *
            FROM chunk_embeddings
            WHERE chunk_id = :chunkId
              AND embedding_set_id = :embeddingSetId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("chunkId", uuid(chunkId.value))
                .addValue("embeddingSetId", uuid(embeddingSetId.value)),
            chunkEmbeddingMapper(json),
        )

    private fun findByIds(ids: List<ChunkEmbeddingId>): List<ChunkEmbedding> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc.query(
                """
                SELECT *
                FROM chunk_embeddings
                WHERE chunk_embedding_id IN (:chunkEmbeddingIds)
                """.trimIndent(),
                MapSqlParameterSource("chunkEmbeddingIds", ids.map { uuid(it.value) }),
                chunkEmbeddingMapper(json),
            )
        }

    private fun saveTypedVectorCopies(chunkEmbeddings: List<ChunkEmbedding>) {
        val dimensionsBySet = embeddingDimensionsBySet(chunkEmbeddings.map { it.embeddingSetId }.toSet())
        val byDimension = chunkEmbeddings.groupBy { chunkEmbedding ->
            val dimension = requireNotNull(dimensionsBySet[chunkEmbedding.embeddingSetId]) {
                "Embedding set ${chunkEmbedding.embeddingSetId.value} was not found"
            }
            require(chunkEmbedding.embedding.values.size == dimension) {
                "Chunk embedding ${chunkEmbedding.id.value} dimension must match embedding set dimension $dimension"
            }
            dimension
        }
        byDimension[2]?.let { insertTypedVectors(it, tableName = "chunk_embedding_vectors_2", vectorType = "vector(2)") }
        byDimension[1536]?.let {
            insertTypedVectors(it, tableName = "chunk_embedding_vectors_1536", vectorType = "vector(1536)")
        }
    }

    private fun embeddingDimensionsBySet(ids: Set<EmbeddingSetId>): Map<EmbeddingSetId, Int> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            jdbc.query(
                """
                SELECT embedding_set_id::text, embedding_dimension
                FROM embedding_sets
                WHERE embedding_set_id IN (:embeddingSetIds)
                """.trimIndent(),
                MapSqlParameterSource("embeddingSetIds", ids.map { uuid(it.value) }),
            ) { rs, _ -> EmbeddingSetId(rs.getString("embedding_set_id")) to rs.getInt("embedding_dimension") }
                .toMap()
        }

    private fun insertTypedVectors(
        chunkEmbeddings: List<ChunkEmbedding>,
        tableName: String,
        vectorType: String,
    ) {
        jdbc.batchUpdate(
            """
            INSERT INTO $tableName (chunk_embedding_id, embedding)
            VALUES (:chunkEmbeddingId, CAST(:embedding AS $vectorType))
            ON CONFLICT (chunk_embedding_id) DO UPDATE SET
                embedding = EXCLUDED.embedding
            """.trimIndent(),
            chunkEmbeddings.map {
                MapSqlParameterSource()
                    .addValue("chunkEmbeddingId", uuid(it.id.value))
                    .addValue("embedding", it.embedding.toPgVectorLiteral())
            }.toTypedArray(),
        )
    }
}

private fun runRecordMapper(json: PostgresJsonSupport): RowMapper<RunRecord> =
    RowMapper { rs, _ ->
        val metadata = json.metadataFromJson(rs.getString("metadata"))
        RunRecord(
            id = RunId(rs.getString("run_id")),
            projectId = ProjectId(rs.getString("project_id")),
            iterationId = IterationId(rs.getString("iteration_id")),
            taskId = TaskId(rs.getString("task_id")),
            sourceRunId = SourceRunId(rs.getString("source_run_id")),
            status = RunStatus.valueOf(rs.getString("status")),
            agentTool = rs.getString("agent_tool"),
            runJson = rs.getString("run_json"),
            artifactRefs = json.artifactRefsFromJson(rs.getString("artifact_refs_json"))
                .map {
                    ArtifactRef(
                        artifactType = ArtifactType.valueOf(it.artifactType),
                        artifactId = it.artifactId,
                        sourcePath = it.sourcePath,
                    )
                },
            startedAt = rs.instant("started_at"),
            finishedAt = rs.nullableInstant("finished_at"),
            sourceReference = json.sourceReferenceFrom(metadata),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.nullableInstant("updated_at"),
            metadata = json.withoutReservedMetadata(metadata),
        )
    }

private fun documentChunkMapper(json: PostgresJsonSupport): RowMapper<DocumentChunk> =
    RowMapper { rs, _ ->
        val metadata = json.metadataFromJson(rs.getString("metadata"))
        DocumentChunk(
            id = DocumentChunkId(rs.getString("chunk_id")),
            projectId = ProjectId(rs.getString("project_id")),
            iterationId = rs.getString("iteration_id")?.let(::IterationId),
            documentId = DocumentId(rs.getString("document_id")),
            taskId = rs.getString("task_id")?.let(::TaskId),
            runId = rs.getString("run_id")?.let(::RunId),
            artifactType = ArtifactType.valueOf(rs.getString("artifact_type")),
            sourcePath = rs.getString("source_path"),
            chunkIndex = rs.getInt("chunk_index"),
            content = rs.getString("content"),
            chunkHash = ContentHash(rs.getString("chunk_hash")),
            tokenEstimate = rs.nullableInt("token_estimate"),
            sourceReference = json.sourceReferenceFrom(metadata),
            createdAt = rs.instant("created_at"),
            metadata = json.withoutReservedMetadata(metadata),
        )
    }

private fun embeddingSetMapper(json: PostgresJsonSupport): RowMapper<EmbeddingSet> =
    RowMapper { rs, _ ->
        EmbeddingSet(
            id = EmbeddingSetId(rs.getString("embedding_set_id")),
            projectId = ProjectId(rs.getString("project_id")),
            embeddingModel = rs.getString("embedding_model"),
            embeddingDimension = rs.getInt("embedding_dimension"),
            embeddingVersion = rs.getString("embedding_version"),
            distanceMetric = distanceMetricFromDbValue(rs.getString("distance_metric")),
            storageType = storageTypeFromDbValue(rs.getString("storage_type")),
            createdAt = rs.instant("created_at"),
            metadata = json.metadataFromJson(rs.getString("metadata")),
        )
    }

private fun chunkEmbeddingMapper(json: PostgresJsonSupport): RowMapper<ChunkEmbedding> =
    RowMapper { rs, _ ->
        ChunkEmbedding(
            id = ChunkEmbeddingId(rs.getString("chunk_embedding_id")),
            embeddingSetId = EmbeddingSetId(rs.getString("embedding_set_id")),
            chunkId = DocumentChunkId(rs.getString("chunk_id")),
            embedding = embeddingFromPgVector(rs.getString("embedding")),
            embeddingHash = rs.getString("embedding_hash")?.let(::ContentHash),
            createdAt = rs.instant("created_at"),
            metadata = json.metadataFromJson(rs.getString("metadata")),
        )
    }

private fun requireIdempotentEmbedding(existing: ChunkEmbedding, requested: ChunkEmbedding): ChunkEmbedding {
    val vectorsMatch = existing.embedding.values == requested.embedding.values
    if (existing.embeddingHash != null || requested.embeddingHash != null) {
        if (existing.embeddingHash == requested.embeddingHash && vectorsMatch) {
            return existing
        }
        throw relationConflict(
            "chunk embedding for chunk ${requested.chunkId.value} and embedding set ${requested.embeddingSetId.value} " +
                "already exists with a different embedding hash or vector",
        )
    }
    if (vectorsMatch) {
        return existing
    }
    throw relationConflict(
        "chunk embedding for chunk ${requested.chunkId.value} and embedding set ${requested.embeddingSetId.value} " +
            "already exists with a different embedding hash or vector",
    )
}

private fun EmbeddingSet.uniqueKey(): String =
    listOf(
        embeddingModel,
        embeddingDimension.toString(),
        embeddingVersion,
        distanceMetric.toDbValue(),
    ).joinToString(separator = ":")

private fun RunRecord.requireSameSourceScope(requested: RunRecord) {
    if (
        projectId != requested.projectId ||
        iterationId != requested.iterationId ||
        taskId != requested.taskId ||
        sourceRunId != requested.sourceRunId
    ) {
        throw relationConflict(
            "run ${requested.id.value} already maps to project ${projectId.value}, " +
                "iteration ${iterationId.value}, task ${taskId.value}, source run ${sourceRunId.value}",
        )
    }
}

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

private fun EmbeddingStorageType.toDbValue(): String =
    when (this) {
        EmbeddingStorageType.INLINE,
        EmbeddingStorageType.VECTOR_INDEX,
        -> "vector"
        EmbeddingStorageType.EXTERNAL -> "external"
    }

private fun storageTypeFromDbValue(value: String): EmbeddingStorageType =
    when (value) {
        "vector" -> EmbeddingStorageType.VECTOR_INDEX
        "external" -> EmbeddingStorageType.EXTERNAL
        else -> error("Unsupported embedding storage type: $value")
    }

private fun Embedding.toPgVectorLiteral(): String {
    require(values.isNotEmpty()) { "Embedding must contain at least one dimension" }
    return values.joinToString(separator = ",", prefix = "[", postfix = "]") {
        require(!it.isNaN() && !it.isInfinite()) { "Embedding values must be finite" }
        it.toString()
    }
}

private fun embeddingFromPgVector(value: String): Embedding {
    val trimmed = value.trim().removePrefix("[").removeSuffix("]")
    if (trimmed.isBlank()) {
        throw IllegalStateException("PostgreSQL vector value is empty")
    }
    return Embedding(trimmed.split(",").map { it.trim().toFloat() })
}

private fun relationConflict(message: String): IllegalStateException =
    IllegalStateException(message)

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

private fun uuid(value: String): UUID =
    UUID.fromString(value)

private fun ResultSet.instant(column: String): Instant =
    getTimestamp(column).toInstant()

private fun ResultSet.nullableInstant(column: String): Instant? =
    getTimestamp(column)?.toInstant()

private fun ResultSet.nullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

private const val MAX_VECTOR_DIMENSION = 2000
