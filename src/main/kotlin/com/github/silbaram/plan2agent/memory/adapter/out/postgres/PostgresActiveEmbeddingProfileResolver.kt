package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolutionException
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolver
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Resolves the one active server-global V2 embedding profile without exposing profile switching.
 *
 * The advisory lock serializes only this bootstrap path. A process that finds partial or
 * incompatible state fails closed; it never repairs or rewrites an existing profile pointer.
 */
@Repository
class PostgresActiveEmbeddingProfileResolver(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: PostgresAdapterMetrics,
    transactionManager: PlatformTransactionManager,
) : ActiveEmbeddingProfileResolver {
    private val transactions = TransactionTemplate(transactionManager)

    override fun resolveActiveV2EmbeddingSetId(): EmbeddingSetId =
        metrics.recordWrite("embedding_profile.resolve_active_v2") {
            try {
                requireNotNull(transactions.execute<EmbeddingSetId> {
                    acquireBootstrapLock()
                    val pointers = activePointers()
                    when {
                        pointers.isEmpty() -> bootstrapOnlyWhenNoServerGlobalSetExists()
                        pointers.size == 1 && pointers.single().scope == ACTIVE_SCOPE ->
                            resolveExistingPointer(pointers.single())
                        else -> throw ActiveEmbeddingProfileResolutionException()
                    }
                })
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) {
                    throw failure
                }
                if (failure is ActiveEmbeddingProfileResolutionException) {
                    throw failure
                }
                throw ActiveEmbeddingProfileResolutionException(failure)
            }
        }

    private fun acquireBootstrapLock() {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(:lockKey)",
            MapSqlParameterSource("lockKey", ACTIVE_PROFILE_LOCK_KEY),
        ) { _, _ -> Unit }
    }

    private fun activePointers(): List<ActiveProfilePointer> =
        jdbc.query(
            """
            SELECT scope, active_embedding_set_id::text AS active_embedding_set_id
            FROM embedding_active_profiles
            ORDER BY scope
            """.trimIndent(),
            MapSqlParameterSource(),
        ) { row, _ ->
            ActiveProfilePointer(
                scope = row.getString("scope"),
                embeddingSetId = row.getString("active_embedding_set_id"),
            )
        }

    private fun bootstrapOnlyWhenNoServerGlobalSetExists(): EmbeddingSetId {
        val serverGlobalSetExists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM embedding_sets WHERE scope = 'SERVER_GLOBAL')",
            MapSqlParameterSource(),
            Boolean::class.java,
        ) ?: throw ActiveEmbeddingProfileResolutionException()
        if (serverGlobalSetExists) {
            throw ActiveEmbeddingProfileResolutionException()
        }

        val profile = V2EmbeddingProfile.fixed
        val embeddingSetId = UUID.randomUUID()
        val createdAt = Timestamp.from(Instant.now())
        jdbc.update(
            """
            INSERT INTO embedding_sets (
                embedding_set_id, project_id, scope, embedding_model, embedding_dimension,
                embedding_version, distance_metric, storage_type, profile_fingerprint,
                profile_manifest, metadata, created_at, updated_at
            ) VALUES (
                :embeddingSetId, NULL, 'SERVER_GLOBAL', :embeddingModel, :embeddingDimension,
                :embeddingVersion, :distanceMetric, 'vector', :profileFingerprint,
                CAST(:profileManifest AS jsonb), '{}'::jsonb, :createdAt, NULL
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("embeddingSetId", embeddingSetId)
                .addValue("embeddingModel", profile.model)
                .addValue("embeddingDimension", profile.dimension)
                .addValue("embeddingVersion", profile.revision)
                .addValue("distanceMetric", profile.distanceMetric.dbValue)
                .addValue("profileFingerprint", profile.fingerprint)
                .addValue("profileManifest", profile.manifest.canonicalJson)
                .addValue("createdAt", createdAt),
        )
        jdbc.update(
            """
            INSERT INTO embedding_active_profiles (
                scope, active_embedding_set_id, created_at, updated_at
            ) VALUES (
                'server_global', :embeddingSetId, :createdAt, NULL
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("embeddingSetId", embeddingSetId)
                .addValue("createdAt", createdAt),
        )
        return EmbeddingSetId(embeddingSetId.toString())
    }

    private fun resolveExistingPointer(pointer: ActiveProfilePointer): EmbeddingSetId {
        val pointerId = try {
            UUID.fromString(pointer.embeddingSetId)
        } catch (failure: IllegalArgumentException) {
            throw ActiveEmbeddingProfileResolutionException(failure)
        }
        val profile = V2EmbeddingProfile.fixed
        val target = jdbc.query(
            """
            SELECT
                embedding_set_id::text AS embedding_set_id,
                project_id::text AS project_id,
                scope,
                profile_fingerprint,
                COALESCE(profile_manifest = CAST(:profileManifest AS jsonb), false) AS manifest_matches,
                embedding_model,
                embedding_dimension,
                embedding_version,
                distance_metric,
                storage_type
            FROM embedding_sets
            WHERE embedding_set_id = :embeddingSetId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("embeddingSetId", pointerId)
                .addValue("profileManifest", profile.manifest.canonicalJson),
        ) { row, _ ->
            ActiveProfileTarget(
                projectId = row.getString("project_id"),
                scope = row.getString("scope"),
                profileFingerprint = row.getString("profile_fingerprint"),
                manifestMatches = row.getBoolean("manifest_matches"),
                embeddingModel = row.getString("embedding_model"),
                embeddingDimension = row.getInt("embedding_dimension"),
                embeddingVersion = row.getString("embedding_version"),
                distanceMetric = row.getString("distance_metric"),
                storageType = row.getString("storage_type"),
            )
        }.singleOrNull() ?: throw ActiveEmbeddingProfileResolutionException()

        if (
            target.projectId != null ||
            target.scope != SERVER_GLOBAL_SCOPE ||
            target.profileFingerprint != profile.fingerprint ||
            !target.manifestMatches ||
            target.embeddingModel != profile.model ||
            target.embeddingDimension != profile.dimension ||
            target.embeddingVersion != profile.revision ||
            target.distanceMetric != profile.distanceMetric.dbValue ||
            target.storageType != VECTOR_STORAGE_TYPE
        ) {
            throw ActiveEmbeddingProfileResolutionException()
        }
        return EmbeddingSetId(pointerId.toString())
    }

    private data class ActiveProfilePointer(
        val scope: String,
        val embeddingSetId: String,
    )

    private data class ActiveProfileTarget(
        val projectId: String?,
        val scope: String,
        val profileFingerprint: String?,
        val manifestMatches: Boolean,
        val embeddingModel: String,
        val embeddingDimension: Int,
        val embeddingVersion: String,
        val distanceMetric: String,
        val storageType: String,
    )

    companion object {
        private const val ACTIVE_SCOPE = "server_global"
        private const val SERVER_GLOBAL_SCOPE = "SERVER_GLOBAL"
        private const val VECTOR_STORAGE_TYPE = "vector"
        private const val ACTIVE_PROFILE_LOCK_KEY = 7_007_202_607L
    }
}

private val DistanceMetric.dbValue: String
    get() = when (this) {
        DistanceMetric.COSINE -> "cosine"
        DistanceMetric.INNER_PRODUCT -> "inner_product"
        DistanceMetric.L2 -> "l2"
    }
