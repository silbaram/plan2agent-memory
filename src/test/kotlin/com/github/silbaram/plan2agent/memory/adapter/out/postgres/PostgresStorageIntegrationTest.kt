@file:Suppress("DEPRECATION")

package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactGraphStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ArtifactQueryPort
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolutionException
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingProfileResolver
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingBackfillStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.ChunkEmbeddingStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentChunkStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.DocumentSnapshotStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingJobStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingSetStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.EnqueueEmbeddingJobCommand
import com.github.silbaram.plan2agent.memory.application.port.out.FindEmbeddingJobsQuery
import com.github.silbaram.plan2agent.memory.application.port.out.IterationStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.PersistedActiveEmbeddingSetResolutionException
import com.github.silbaram.plan2agent.memory.application.port.out.PersistedActiveEmbeddingSetResolver
import com.github.silbaram.plan2agent.memory.application.port.out.StructurallyValidPersistedActiveEmbeddingSetResolver
import com.github.silbaram.plan2agent.memory.application.port.out.ProjectStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.RunRecordStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskGraphStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.TaskStorePort
import com.github.silbaram.plan2agent.memory.application.usecase.DocumentChunkWrite
import com.github.silbaram.plan2agent.memory.application.usecase.FindArtifactsQuery
import com.github.silbaram.plan2agent.memory.application.usecase.GraphTraceDirection
import com.github.silbaram.plan2agent.memory.application.usecase.GraphNodeSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.GraphTraceQuery
import com.github.silbaram.plan2agent.memory.application.usecase.SaveArtifactGraphSnapshotCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentChunksCommand
import com.github.silbaram.plan2agent.memory.application.usecase.SaveDocumentSnapshotCommand
import com.github.silbaram.plan2agent.memory.application.usecase.WriteUseCaseService
import com.github.silbaram.plan2agent.memory.application.worker.EmbeddingJobCompletionService
import com.github.silbaram.plan2agent.memory.application.worker.EmbeddingJobWorker
import com.github.silbaram.plan2agent.memory.application.worker.EmbeddingWorkerProperties
import com.github.silbaram.plan2agent.memory.domain.ArtifactEdge
import com.github.silbaram.plan2agent.memory.domain.ArtifactEdgeId
import com.github.silbaram.plan2agent.memory.domain.ArtifactEdgeType
import com.github.silbaram.plan2agent.memory.domain.ArtifactNode
import com.github.silbaram.plan2agent.memory.domain.ArtifactNodeId
import com.github.silbaram.plan2agent.memory.domain.ArtifactNodeKind
import com.github.silbaram.plan2agent.memory.domain.ArtifactRef
import com.github.silbaram.plan2agent.memory.domain.ArtifactType
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.ChunkEmbedding
import com.github.silbaram.plan2agent.memory.domain.ChunkEmbeddingId
import com.github.silbaram.plan2agent.memory.domain.ContentHash
import com.github.silbaram.plan2agent.memory.domain.DistanceMetric
import com.github.silbaram.plan2agent.memory.domain.DocumentChunk
import com.github.silbaram.plan2agent.memory.domain.DocumentChunkId
import com.github.silbaram.plan2agent.memory.domain.DocumentId
import com.github.silbaram.plan2agent.memory.domain.DocumentSnapshot
import com.github.silbaram.plan2agent.memory.domain.Embedding
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJob
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobErrorCode
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobFailure
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobStatus
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSet
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetScope
import com.github.silbaram.plan2agent.memory.domain.EmbeddingStorageType
import com.github.silbaram.plan2agent.memory.domain.Iteration
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.IterationStatus
import com.github.silbaram.plan2agent.memory.domain.Project
import com.github.silbaram.plan2agent.memory.domain.ProjectId
import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.RunRecord
import com.github.silbaram.plan2agent.memory.domain.RunStatus
import com.github.silbaram.plan2agent.memory.domain.SourceDocumentId
import com.github.silbaram.plan2agent.memory.domain.SourceIterationId
import com.github.silbaram.plan2agent.memory.domain.SourceProjectId
import com.github.silbaram.plan2agent.memory.domain.SourceReference
import com.github.silbaram.plan2agent.memory.domain.SourceRunId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskGraphId
import com.github.silbaram.plan2agent.memory.domain.SourceTaskId
import com.github.silbaram.plan2agent.memory.domain.Task
import com.github.silbaram.plan2agent.memory.domain.TaskDependency
import com.github.silbaram.plan2agent.memory.domain.TaskGraph
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId
import com.github.silbaram.plan2agent.memory.domain.TaskStatus
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import com.github.silbaram.plan2agent.memory.support.FakeEmbeddingFailure
import com.github.silbaram.plan2agent.memory.support.FakeEmbeddingMode
import com.github.silbaram.plan2agent.memory.support.FakeEmbeddingPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.groups.Tuple.tuple
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["p2a.embedding.provider=none", "p2a.memory.embedding.worker.enabled=false"])
class PostgresStorageIntegrationTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var projectStore: ProjectStorePort

    @Autowired
    private lateinit var iterationStore: IterationStorePort

    @Autowired
    private lateinit var documentSnapshotStore: DocumentSnapshotStorePort

    @Autowired
    private lateinit var taskGraphStore: TaskGraphStorePort

    @Autowired
    private lateinit var taskStore: TaskStorePort

    @Autowired
    private lateinit var runRecordStore: RunRecordStorePort

    @Autowired
    private lateinit var documentChunkStore: DocumentChunkStorePort

    @Autowired
    private lateinit var chunkEmbeddingStore: ChunkEmbeddingStorePort

    @Autowired
    private lateinit var embeddingSetStore: EmbeddingSetStorePort

    @Autowired
    private lateinit var activeEmbeddingProfileResolver: ActiveEmbeddingProfileResolver

    @Autowired
    private lateinit var persistedActiveEmbeddingSetResolver: PersistedActiveEmbeddingSetResolver

    @Autowired
    private lateinit var structurallyValidActiveEmbeddingSetResolver: StructurallyValidPersistedActiveEmbeddingSetResolver

    @Autowired
    private lateinit var embeddingPort: EmbeddingPort

    @Autowired
    private lateinit var embeddingJobStore: EmbeddingJobStorePort

    @Autowired
    private lateinit var activeEmbeddingBackfillStore: ActiveEmbeddingBackfillStorePort

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var artifactQuery: ArtifactQueryPort

    @Autowired
    private lateinit var artifactGraphStore: ArtifactGraphStorePort

    @Autowired
    private lateinit var writeUseCase: WriteUseCaseService

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute(
            """
            TRUNCATE TABLE
                artifact_edges,
                artifact_nodes,
                embedding_active_profiles,
                embedding_jobs,
                chunk_embeddings,
                embedding_sets,
                document_chunks,
                runs,
                tasks,
                task_graphs,
                documents,
                iterations,
                projects
            RESTART IDENTITY CASCADE
            """.trimIndent(),
        )
    }

    @Test
    fun `flyway migration creates pgvector schema contracts`() {
        assertThat(
            jdbc.queryForObject(
                "SELECT current_setting('server_version_num')::integer / 10000",
                Int::class.java,
            ),
        ).isEqualTo(17)
        assertThat(
            jdbc.queryForObject(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                String::class.java,
            ),
        ).isEqualTo("0.8.5")
        assertThat(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')",
                Boolean::class.java,
            ),
        ).isTrue()
        assertThat(tableNames()).contains(
            "projects",
            "iterations",
            "documents",
            "task_graphs",
            "tasks",
            "runs",
            "document_chunks",
            "embedding_sets",
            "chunk_embeddings",
            "embedding_jobs",
            "chunk_embedding_vectors_2",
            "chunk_embedding_vectors_384",
            "chunk_embedding_vectors_1536",
            "artifact_nodes",
            "artifact_edges",
        )
        assertThat(columnNames("documents")).contains(
            "document_id",
            "source_document_id",
            "source_path",
            "raw_source_path",
            "content_hash",
            "snapshot_version",
        )
        assertThat(columnNames("document_chunks")).contains(
            "chunk_id",
            "document_id",
            "task_id",
            "run_id",
            "raw_source_path",
            "chunk_hash",
        )
        assertThat(columnNames("embedding_sets")).contains(
            "scope",
            "profile_fingerprint",
            "profile_manifest",
        )
        assertThat(columnNames("embedding_jobs")).contains(
            "embedding_job_id",
            "chunk_id",
            "embedding_set_id",
            "status",
            "attempt_count",
            "next_attempt_at",
            "lease_owner",
            "lease_generation",
            "lease_expires_at",
            "last_error_code",
            "last_error_message",
        )
        assertThat(indexAndConstraintNames()).contains(
            "uq_documents_logical_snapshot_hash",
            "uq_documents_logical_snapshot_version",
            "uq_documents_project_iteration_source_document_hash",
            "uq_task_graphs_project_iteration_source_task_graph_id",
            "uq_document_chunks_document_chunk_hash",
            "uq_chunk_embeddings_chunk_embedding_set",
            "idx_documents_latest_snapshot",
            "idx_document_chunks_artifact_filters",
            "idx_chunk_embeddings_embedding_set_id",
            "idx_chunk_embedding_vectors_2_hnsw_cosine",
            "idx_chunk_embedding_vectors_384_hnsw_cosine",
            "idx_chunk_embedding_vectors_1536_hnsw_cosine",
            "uq_embedding_sets_legacy_model_dimension_version_metric",
            "uq_embedding_sets_server_global_profile_fingerprint",
            "uq_embedding_jobs_chunk_embedding_set",
            "idx_embedding_jobs_claimable",
            "idx_embedding_jobs_expired_leases",
            "ck_embedding_jobs_status",
            "ck_embedding_jobs_error_code",
            "ck_embedding_jobs_error_message",
            "ck_embedding_jobs_error_fields",
            "ck_embedding_jobs_state_leases",
            "ck_embedding_jobs_completed_states",
            "uq_artifact_nodes_scope_natural_key",
            "uq_artifact_edges_nodes_type",
            "ck_artifact_edges_no_self_loop",
            "idx_artifact_edges_project_from",
            "idx_artifact_edges_project_to",
        )
        assertThat(indexAndConstraintNames())
            .doesNotContain("uq_task_graphs_project_iteration_graph_hash")
        assertThat(constraintDefinition("ck_embedding_jobs_status")).contains(
            "pending",
            "running",
            "retrying",
            "succeeded",
            "permanently_failed",
        )
        assertThat(constraintDefinition("ck_embedding_jobs_error_code")).contains(
            "provider_unavailable",
            "provider_contract_invalid",
            "content_invalid",
            "max_attempts_exhausted",
            "lease_expired",
        ).doesNotContain(
            "provider_timeout",
            "provider_response_invalid",
            "input_validation",
            "unknown",
        )
        assertThat(
            jdbc.queryForObject(
                """
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'task_graphs'
                  AND column_name = 'source_task_graph_id'
                """.trimIndent(),
                String::class.java,
            ),
        ).isEqualTo("NO")
        assertThat(
            strings(
                """
                SELECT version
                FROM flyway_schema_history
                WHERE success
                  AND version IS NOT NULL
                ORDER BY installed_rank
                """.trimIndent(),
            ),
        ).containsExactly("1", "2", "3")
    }

    @Test
    fun `embedding jobs are idempotent and lease transitions use compare and swap`() {
        val current = Instant.now()
        val fixture = saveFixture("embedding-job")
        val embeddingSet = embeddingSetStore.resolveOrCreate(
            embeddingSet(
                scope = "embedding-job",
                projectId = fixture.project.id,
                model = "embedding-job-model",
                version = "v1",
            ),
        )
        val jobId = EmbeddingJobId(stableUuid("embedding-job"))
        val enqueued = embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(jobId, fixture.chunk.id, embeddingSet.id, current.minusSeconds(1)),
        )
        val duplicate = embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(
                EmbeddingJobId(stableUuid("embedding-job-duplicate")),
                fixture.chunk.id,
                embeddingSet.id,
                current,
            ),
        )

        assertThat(enqueued.status).isEqualTo(EmbeddingJobStatus.PENDING)
        assertThat(duplicate.id).isEqualTo(jobId)
        assertThat(enqueued.attemptCount).isZero()
        assertThat(rowCount("embedding_jobs")).isEqualTo(1)

        val claimed = embeddingJobStore.claimDue(1, "worker-a", current.plusSeconds(600)).single()
        assertThat(claimed).extracting(
            { it.status },
            { it.attemptCount },
            { it.leaseGeneration },
            { it.leaseOwner },
        ).containsExactly(EmbeddingJobStatus.RUNNING, 1, 1L, "worker-a")
        assertThat(
            embeddingJobStore.releaseClaimToPending(claimed.id, "another-worker", claimed.leaseGeneration),
        ).isNull()
        val released = requireNotNull(
            embeddingJobStore.releaseClaimToPending(claimed.id, "worker-a", claimed.leaseGeneration),
        )
        assertThat(released).extracting({ it.status }, { it.attemptCount })
            .containsExactly(EmbeddingJobStatus.PENDING, 0)
        val claimedForRetry = embeddingJobStore.claimDue(1, "worker-a", current.plusSeconds(600)).single()

        val failure = EmbeddingJobFailure.fromUntrustedMessage(
            EmbeddingJobErrorCode.PROVIDER_CONTRACT_INVALID,
            "temporary timeout\n" + "x".repeat(600),
        )
        val retried = requireNotNull(
            embeddingJobStore.markRetrying(
                claimedForRetry.id,
                "worker-a",
                claimedForRetry.leaseGeneration,
                failure,
                nextAttemptAt = current.minusSeconds(1),
            ),
        )
        assertThat(retried.status).isEqualTo(EmbeddingJobStatus.RETRYING)
        assertThat(retried.lastError?.code).isEqualTo(EmbeddingJobErrorCode.PROVIDER_CONTRACT_INVALID)
        assertThat(retried.lastError?.message).doesNotContain("\n")
        assertThat(retried.lastError?.message?.codePointCount(0, retried.lastError.message.length)).isLessThanOrEqualTo(512)

        val reclaimed = embeddingJobStore.claimDue(1, "worker-b", current.plusSeconds(600)).single()
        assertThat(reclaimed).extracting({ it.attemptCount }, { it.leaseGeneration })
            .containsExactly(2, 3L)
        assertThat(
            embeddingJobStore.markPermanentlyFailed(
                claimedForRetry.id,
                "worker-a",
                claimedForRetry.leaseGeneration,
                failure,
            ),
        ).isNull()

        val permanentlyFailed = requireNotNull(
            embeddingJobStore.markPermanentlyFailed(
                reclaimed.id,
                "worker-b",
                reclaimed.leaseGeneration,
                failure,
            ),
        )
        assertThat(permanentlyFailed.status).isEqualTo(EmbeddingJobStatus.PERMANENTLY_FAILED)
        val manuallyRetried = requireNotNull(
            embeddingJobStore.retryFailed(jobId),
        )
        assertThat(manuallyRetried).extracting(
            { it.status },
            { it.attemptCount },
            { it.lastError },
            { it.completedAt },
        ).containsExactly(EmbeddingJobStatus.PENDING, 0, null, null)
        assertThat(embeddingJobStore.retryFailed(jobId)).isEqualTo(manuallyRetried)

        val thirdClaim = embeddingJobStore.claimDue(1, "worker-c", Instant.now().minusSeconds(1)).single()
        val recovered = embeddingJobStore.recoverExpiredLeases()
        assertThat(recovered).extracting({ it.id }, { it.status }, { it.lastError?.code })
            .containsExactly(tuple(jobId, EmbeddingJobStatus.PENDING, EmbeddingJobErrorCode.LEASE_EXPIRED))
        assertThat(thirdClaim).extracting({ it.attemptCount }, { it.leaseGeneration })
            .containsExactly(1, 4L)
        assertThat(embeddingJobStore.findById(jobId)).isEqualTo(recovered.single())

        val finalClaim = embeddingJobStore.claimDue(1, "worker-d", Instant.now().plusSeconds(600)).single()
        assertThat(
            embeddingJobStore.markSucceeded(finalClaim.id, "worker-d", finalClaim.leaseGeneration),
        ).extracting { it?.status }.isEqualTo(EmbeddingJobStatus.SUCCEEDED)
        assertThat(embeddingJobStore.retryFailed(jobId)?.status).isEqualTo(EmbeddingJobStatus.SUCCEEDED)
    }

    @Test
    fun `concurrent embedding job claims do not duplicate work and fence same-owner reclaims`() {
        val current = Instant.now()
        val fixture = saveFixture("embedding-job-concurrent-claim")
        val embeddingSet = embeddingSetStore.resolveOrCreate(
            embeddingSet(
                scope = "embedding-job-concurrent-claim",
                projectId = fixture.project.id,
                model = "embedding-job-concurrent-claim-model",
                version = "v1",
            ),
        )
        val jobId = EmbeddingJobId(stableUuid("embedding-job-concurrent-claim"))
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(jobId, fixture.chunk.id, embeddingSet.id, current.minusSeconds(1)),
        )

        val workerCount = 2
        val ready = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val claims = (1..workerCount).map {
                executor.submit<List<EmbeddingJob>> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "concurrent embedding job claim start timed out" }
                    embeddingJobStore.claimDue(1, "worker-a", current.plusSeconds(600))
                }
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            val firstClaim = claims.flatMap { it.get(20, TimeUnit.SECONDS) }.single()
            assertThat(firstClaim).extracting(
                { it.id },
                { it.status },
                { it.attemptCount },
                { it.leaseOwner },
                { it.leaseGeneration },
            ).containsExactly(jobId, EmbeddingJobStatus.RUNNING, 1, "worker-a", 1L)

            requireNotNull(
                embeddingJobStore.releaseClaimToPending(
                    firstClaim.id,
                    "worker-a",
                    firstClaim.leaseGeneration,
                ),
            )
            val reclaimed = embeddingJobStore.claimDue(1, "worker-a", current.plusSeconds(600)).single()
            assertThat(reclaimed).extracting({ it.attemptCount }, { it.leaseGeneration })
                .containsExactly(1, 2L)

            assertThat(
                embeddingJobStore.markSucceeded(firstClaim.id, "worker-a", firstClaim.leaseGeneration),
            ).isNull()
            assertThat(
                embeddingJobStore.releaseClaimToPending(firstClaim.id, "worker-a", firstClaim.leaseGeneration),
            ).isNull()
            assertThat(embeddingJobStore.findById(jobId)).isEqualTo(reclaimed)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `embedding job lease transitions reject stale and expired workers`() {
        val current = Instant.now()
        val fixture = saveFixture("embedding-job-fenced-transition")
        val embeddingSet = embeddingSetStore.resolveOrCreate(
            embeddingSet(
                scope = "embedding-job-fenced-transition",
                projectId = fixture.project.id,
                model = "embedding-job-fenced-transition-model",
                version = "v1",
            ),
        )
        val jobId = EmbeddingJobId(stableUuid("embedding-job-fenced-transition"))
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(jobId, fixture.chunk.id, embeddingSet.id, current.minusSeconds(1)),
        )
        val firstClaim = embeddingJobStore.claimDue(1, "worker-a", current.plusSeconds(600)).single()
        requireNotNull(
            embeddingJobStore.releaseClaimToPending(firstClaim.id, "worker-a", firstClaim.leaseGeneration),
        )
        val currentClaim = embeddingJobStore.claimDue(1, "worker-a", current.plusSeconds(600)).single()
        val failure = EmbeddingJobFailure.fromUntrustedMessage(
            EmbeddingJobErrorCode.PROVIDER_UNAVAILABLE,
            "provider unavailable",
        )

        assertThat(
            embeddingJobStore.markRetrying(
                firstClaim.id,
                "worker-a",
                firstClaim.leaseGeneration,
                failure,
                current.plusSeconds(60),
            ),
        ).isNull()
        assertThat(
            embeddingJobStore.markPermanentlyFailed(
                firstClaim.id,
                "worker-a",
                firstClaim.leaseGeneration,
                failure,
            ),
        ).isNull()
        assertThat(embeddingJobStore.findById(jobId)).isEqualTo(currentClaim)

        jdbc.update(
            "UPDATE embedding_jobs SET lease_expires_at = now() - INTERVAL '1 second' WHERE embedding_job_id = ?",
            UUID.fromString(jobId.value),
        )
        assertThat(
            embeddingJobStore.markSucceeded(currentClaim.id, "worker-a", currentClaim.leaseGeneration),
        ).isNull()
        assertThat(
            embeddingJobStore.releaseClaimToPending(currentClaim.id, "worker-a", currentClaim.leaseGeneration),
        ).isNull()
        assertThat(embeddingJobStore.findById(jobId)).extracting(
            { it?.status },
            { it?.leaseOwner },
            { it?.leaseGeneration },
        ).containsExactly(EmbeddingJobStatus.RUNNING, "worker-a", currentClaim.leaseGeneration)
    }

    @Test
    fun `expired same-owner reclaim fences the previous generation`() {
        val fixture = saveFixture("embedding-job-expired-same-owner")
        val embeddingSet = embeddingSetStore.resolveOrCreate(
            embeddingSet(
                scope = "embedding-job-expired-same-owner",
                projectId = fixture.project.id,
                model = "embedding-job-expired-same-owner-model",
                version = "v1",
            ),
        )
        val jobId = EmbeddingJobId(stableUuid("embedding-job-expired-same-owner"))
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(jobId, fixture.chunk.id, embeddingSet.id, Instant.now().minusSeconds(1)),
        )

        val expiredClaim = embeddingJobStore.claimDue(1, "worker-a", Instant.now().minusSeconds(1)).single()
        assertThat(embeddingJobStore.recoverExpiredLeases()).singleElement().extracting(
            { it.status },
            { it.attemptCount },
            { it.leaseGeneration },
        ).containsExactly(EmbeddingJobStatus.PENDING, 1, expiredClaim.leaseGeneration)

        val currentClaim = embeddingJobStore.claimDue(1, "worker-a", Instant.now().plusSeconds(600)).single()
        val failure = EmbeddingJobFailure.fromUntrustedMessage(
            EmbeddingJobErrorCode.PROVIDER_UNAVAILABLE,
            "provider unavailable",
        )
        assertThat(currentClaim).extracting({ it.attemptCount }, { it.leaseGeneration })
            .containsExactly(2, expiredClaim.leaseGeneration + 1)
        assertThat(embeddingJobStore.markSucceeded(jobId, "worker-a", expiredClaim.leaseGeneration)).isNull()
        assertThat(
            embeddingJobStore.markRetrying(
                jobId,
                "worker-a",
                expiredClaim.leaseGeneration,
                failure,
                Instant.now().plusSeconds(5),
            ),
        ).isNull()
        assertThat(embeddingJobStore.releaseClaimToPending(jobId, "worker-a", expiredClaim.leaseGeneration)).isNull()
        assertThat(embeddingJobStore.findById(jobId)).isEqualTo(currentClaim)
    }

    @Test
    fun `worker retries provider failures with deterministic backoff then exhausts attempts`() {
        val fixture = saveFixture("embedding-worker-retry")
        val activeEmbeddingSetId = ensurePersistedActiveEmbeddingTarget()
        val jobId = EmbeddingJobId(stableUuid("embedding-worker-retry-job"))
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(jobId, fixture.chunk.id, activeEmbeddingSetId, now.minusSeconds(1)),
        )
        val fake = FakeEmbeddingPort(
            activeEmbeddingTarget = ActiveEmbeddingTarget(V2EmbeddingProfile.fixed, activeEmbeddingSetId),
        ).fail(FakeEmbeddingMode.DOCUMENT, FakeEmbeddingFailure.RETRYABLE_UNAVAILABLE)
        val executor = Executors.newSingleThreadExecutor()
        val properties = EmbeddingWorkerProperties(
            maxAttempts = 4,
            initialRetryDelay = java.time.Duration.ofSeconds(5),
            maxRetryDelay = java.time.Duration.ofSeconds(12),
        )

        try {
            val worker = newEmbeddingWorker(fake, executor, properties, Clock.fixed(now, ZoneOffset.UTC))
            worker.poll()
            assertThat(embeddingJobStore.findById(jobId)).extracting(
                { it?.status },
                { it?.attemptCount },
                { it?.nextAttemptAt },
                { it?.lastError?.code },
            ).containsExactly(
                EmbeddingJobStatus.RETRYING,
                1,
                now.plusSeconds(5),
                EmbeddingJobErrorCode.PROVIDER_UNAVAILABLE,
            )

            makeEmbeddingJobDue(jobId)
            worker.poll()
            assertThat(embeddingJobStore.findById(jobId)).extracting(
                { it?.status },
                { it?.attemptCount },
                { it?.nextAttemptAt },
            ).containsExactly(EmbeddingJobStatus.RETRYING, 2, now.plusSeconds(10))

            makeEmbeddingJobDue(jobId)
            worker.poll()
            assertThat(embeddingJobStore.findById(jobId)).extracting(
                { it?.status },
                { it?.attemptCount },
                { it?.nextAttemptAt },
            ).containsExactly(EmbeddingJobStatus.RETRYING, 3, now.plusSeconds(12))

            makeEmbeddingJobDue(jobId)
            worker.poll()
            assertThat(embeddingJobStore.findById(jobId)).extracting(
                { it?.status },
                { it?.attemptCount },
                { it?.lastError?.code },
                { it?.lastError?.message },
            ).containsExactly(
                EmbeddingJobStatus.PERMANENTLY_FAILED,
                4,
                EmbeddingJobErrorCode.MAX_ATTEMPTS_EXHAUSTED,
                "Embedding provider remained unavailable after 4 attempts",
            )
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `worker holds jobs pending until provider recovery and releases contract failures`() {
        val activeEmbeddingSetId = ensurePersistedActiveEmbeddingTarget()
        EmbeddingProviderState.entries
            .filter { it != EmbeddingProviderState.READY }
            .forEach { state ->
                val fixture = saveFixture("embedding-worker-provider-$state")
                val jobId = EmbeddingJobId(stableUuid("embedding-worker-provider-$state"))
                embeddingJobStore.enqueueMissing(
                    EnqueueEmbeddingJobCommand(jobId, fixture.chunk.id, activeEmbeddingSetId, Instant.now().minusSeconds(1)),
                )
                val fake = FakeEmbeddingPort(
                    activeEmbeddingTarget = ActiveEmbeddingTarget(V2EmbeddingProfile.fixed, activeEmbeddingSetId),
                    providerState = state,
                )
                val executor = Executors.newSingleThreadExecutor()

                try {
                    val worker = newEmbeddingWorker(fake, executor)
                    worker.poll()
                    assertThat(embeddingJobStore.findById(jobId)).extracting(
                        { it?.status },
                        { it?.attemptCount },
                        { it?.leaseOwner },
                    ).containsExactly(EmbeddingJobStatus.PENDING, 0, null)
                    assertThat(fake.requests).isEmpty()

                    fake.providerState = EmbeddingProviderState.READY
                    worker.poll()
                    assertThat(embeddingJobStore.findById(jobId)).extracting(
                        { it?.status },
                        { it?.attemptCount },
                    ).containsExactly(EmbeddingJobStatus.SUCCEEDED, 1)
                } finally {
                    executor.shutdownNow()
                    executor.awaitTermination(10, TimeUnit.SECONDS)
                }
            }

        val fixture = saveFixture("embedding-worker-contract-release")
        val jobId = EmbeddingJobId(stableUuid("embedding-worker-contract-release-job"))
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(jobId, fixture.chunk.id, activeEmbeddingSetId, Instant.now().minusSeconds(1)),
        )
        val fake = FakeEmbeddingPort(
            activeEmbeddingTarget = ActiveEmbeddingTarget(V2EmbeddingProfile.fixed, activeEmbeddingSetId),
        ).fail(FakeEmbeddingMode.DOCUMENT, FakeEmbeddingFailure.CONTRACT_VIOLATION)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val worker = newEmbeddingWorker(fake, executor)
            worker.poll()
            assertThat(embeddingJobStore.findById(jobId)).extracting(
                { it?.status },
                { it?.attemptCount },
                { it?.leaseOwner },
                { it?.leaseExpiresAt },
                { it?.lastError },
                { it?.leaseGeneration },
            ).containsExactly(EmbeddingJobStatus.PENDING, 0, null, null, null, 1L)

            fake.clearFailure(FakeEmbeddingMode.DOCUMENT)
            worker.poll()
            assertThat(embeddingJobStore.findById(jobId)).extracting(
                { it?.status },
                { it?.attemptCount },
                { it?.leaseGeneration },
            ).containsExactly(EmbeddingJobStatus.SUCCEEDED, 1, 2L)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `ready worker processes only the exact active target and persists its 384 mirror`() {
        val fixture = saveFixture("embedding-worker-process")
        val activeEmbeddingSetId = ensurePersistedActiveEmbeddingTarget()
        val foreignEmbeddingSet = embeddingSetStore.resolveOrCreate(
            embeddingSet(
                scope = "embedding-worker-foreign-target",
                projectId = fixture.project.id,
                model = "foreign-worker-model",
                version = "v1",
            ),
        )
        val activeJobId = EmbeddingJobId(stableUuid("embedding-worker-active-job"))
        val foreignJobId = EmbeddingJobId(stableUuid("embedding-worker-foreign-job"))
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(activeJobId, fixture.chunk.id, activeEmbeddingSetId, Instant.now().minusSeconds(1)),
        )
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(foreignJobId, fixture.chunk.id, foreignEmbeddingSet.id, Instant.now().minusSeconds(1)),
        )
        val fake = FakeEmbeddingPort(
            activeEmbeddingTarget = ActiveEmbeddingTarget(V2EmbeddingProfile.fixed, activeEmbeddingSetId),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            newEmbeddingWorker(fake, executor).poll()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }

        assertThat(embeddingJobStore.findById(activeJobId)?.status).isEqualTo(EmbeddingJobStatus.SUCCEEDED)
        assertThat(embeddingJobStore.findById(foreignJobId)?.status).isEqualTo(EmbeddingJobStatus.PENDING)
        assertThat(fake.requests).singleElement().extracting({ it.mode }, { it.input })
            .containsExactly(FakeEmbeddingMode.DOCUMENT, fixture.chunk.content)
        assertThat(rowCount("chunk_embeddings")).isEqualTo(1)
        assertThat(rowCount("chunk_embedding_vectors_384")).isEqualTo(1)
    }

    @Test
    fun `worker reclaims an expired lease and processes it with a new generation`() {
        val fixture = saveFixture("embedding-worker-reclaim")
        val activeEmbeddingSetId = ensurePersistedActiveEmbeddingTarget()
        val jobId = EmbeddingJobId(stableUuid("embedding-worker-reclaim-job"))
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(jobId, fixture.chunk.id, activeEmbeddingSetId, Instant.now().minusSeconds(1)),
        )
        val crashedClaim = embeddingJobStore.claimDueForEmbeddingSet(
            embeddingSetId = activeEmbeddingSetId,
            batchSize = 1,
            owner = "crashed-worker",
            leaseUntil = Instant.now().minusSeconds(1),
        ).single()
        val fake = FakeEmbeddingPort(
            activeEmbeddingTarget = ActiveEmbeddingTarget(V2EmbeddingProfile.fixed, activeEmbeddingSetId),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            newEmbeddingWorker(fake, executor).poll()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }

        assertThat(embeddingJobStore.findById(jobId)).extracting(
            { it?.status },
            { it?.attemptCount },
            { it?.leaseGeneration },
        ).containsExactly(EmbeddingJobStatus.SUCCEEDED, 2, crashedClaim.leaseGeneration + 1)
        assertThat(fake.requests).singleElement().extracting { it.input }.isEqualTo(fixture.chunk.content)
        assertThat(rowCount("chunk_embeddings")).isEqualTo(1)
        assertThat(rowCount("chunk_embedding_vectors_384")).isEqualTo(1)
    }

    @Test
    fun `stale completion rolls back its untyped and typed vector writes`() {
        val fixture = saveFixture("embedding-worker-stale-completion")
        val activeEmbeddingSetId = ensurePersistedActiveEmbeddingTarget()
        val jobId = EmbeddingJobId(stableUuid("embedding-worker-stale-completion-job"))
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(jobId, fixture.chunk.id, activeEmbeddingSetId, Instant.now().minusSeconds(1)),
        )
        val replacementClaim = AtomicReference<EmbeddingJob>()
        val fake = FakeEmbeddingPort(
            activeEmbeddingTarget = ActiveEmbeddingTarget(V2EmbeddingProfile.fixed, activeEmbeddingSetId),
        ).beforeNextEmbed(FakeEmbeddingMode.DOCUMENT) {
            jdbc.update(
                "UPDATE embedding_jobs SET lease_expires_at = now() - INTERVAL '1 second' WHERE embedding_job_id = ?",
                UUID.fromString(jobId.value),
            )
            embeddingJobStore.recoverExpiredLeases()
            replacementClaim.set(
                embeddingJobStore.claimDueForEmbeddingSet(
                    embeddingSetId = activeEmbeddingSetId,
                    batchSize = 1,
                    owner = "replacement-worker",
                    leaseUntil = Instant.now().plusSeconds(120),
                ).single(),
            )
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            newEmbeddingWorker(fake, executor).poll()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }

        val currentClaim = requireNotNull(replacementClaim.get())
        assertThat(embeddingJobStore.findById(jobId)).extracting(
            { it?.status },
            { it?.leaseOwner },
            { it?.leaseGeneration },
        ).containsExactly(EmbeddingJobStatus.RUNNING, "replacement-worker", currentClaim.leaseGeneration)
        assertThat(currentClaim.leaseGeneration).isEqualTo(2L)
        assertThat(rowCount("chunk_embeddings")).isZero()
        assertThat(rowCount("chunk_embedding_vectors_384")).isZero()
    }

    @Test
    fun `worker handles deleted and invalid chunks without creating vectors`() {
        val deletedFixture = saveFixture("embedding-worker-deleted")
        val activeEmbeddingSetId = ensurePersistedActiveEmbeddingTarget()
        val deletedJobId = EmbeddingJobId(stableUuid("embedding-worker-deleted-job"))
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(deletedJobId, deletedFixture.chunk.id, activeEmbeddingSetId, Instant.now().minusSeconds(1)),
        )
        val deletedChunkFake = FakeEmbeddingPort(
            activeEmbeddingTarget = ActiveEmbeddingTarget(V2EmbeddingProfile.fixed, activeEmbeddingSetId),
        ).beforeNextEmbed(FakeEmbeddingMode.DOCUMENT) {
            jdbc.update("DELETE FROM document_chunks WHERE chunk_id = ?", UUID.fromString(deletedFixture.chunk.id.value))
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            newEmbeddingWorker(deletedChunkFake, executor).poll()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }

        val invalidFixture = saveFixture("embedding-worker-invalid")
        val invalidJobId = EmbeddingJobId(stableUuid("embedding-worker-invalid-job"))
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(invalidJobId, invalidFixture.chunk.id, activeEmbeddingSetId, Instant.now().minusSeconds(1)),
        )
        jdbc.update("UPDATE document_chunks SET content = ' ' WHERE chunk_id = ?", UUID.fromString(invalidFixture.chunk.id.value))
        val invalidChunkFake = FakeEmbeddingPort(
            activeEmbeddingTarget = ActiveEmbeddingTarget(V2EmbeddingProfile.fixed, activeEmbeddingSetId),
        )
        val invalidExecutor = Executors.newSingleThreadExecutor()

        try {
            newEmbeddingWorker(invalidChunkFake, invalidExecutor).poll()
            newEmbeddingWorker(invalidChunkFake, invalidExecutor).poll()
        } finally {
            invalidExecutor.shutdownNow()
            invalidExecutor.awaitTermination(10, TimeUnit.SECONDS)
        }

        assertThat(embeddingJobStore.findById(deletedJobId)).isNull()
        assertThat(embeddingJobStore.findById(invalidJobId)).extracting(
            { it?.status },
            { it?.lastError?.code },
        ).containsExactly(EmbeddingJobStatus.PERMANENTLY_FAILED, EmbeddingJobErrorCode.CONTENT_INVALID)
        assertThat(deletedChunkFake.requests).singleElement().extracting { it.input }.isEqualTo(deletedFixture.chunk.content)
        assertThat(invalidChunkFake.requests).isEmpty()
        assertThat(rowCount("chunk_embeddings")).isZero()
        assertThat(rowCount("chunk_embedding_vectors_384")).isZero()
    }

    @Test
    fun `embedding job page returns stable pages by creation order`() {
        val current = Instant.now()
        val firstFixture = saveFixture("embedding-job-page-first")
        val secondFixture = saveFixture("embedding-job-page-second")
        val embeddingSet = embeddingSetStore.resolveOrCreate(
            embeddingSet(
                scope = "embedding-job-page",
                projectId = firstFixture.project.id,
                model = "embedding-job-page-model",
                version = "v1",
            ),
        )
        val firstJobId = EmbeddingJobId(stableUuid("embedding-job-page-first"))
        val secondJobId = EmbeddingJobId(stableUuid("embedding-job-page-second"))
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(firstJobId, firstFixture.chunk.id, embeddingSet.id, current.minusSeconds(1)),
        )
        embeddingJobStore.enqueueMissing(
            EnqueueEmbeddingJobCommand(secondJobId, secondFixture.chunk.id, embeddingSet.id, current),
        )

        val query = FindEmbeddingJobsQuery(statuses = setOf(EmbeddingJobStatus.PENDING), limit = 1)
        val firstPage = embeddingJobStore.findPage(query)
        val secondPage = embeddingJobStore.findPage(query.copy(cursor = requireNotNull(firstPage.nextCursor)))

        assertThat(firstPage.items.map { it.id }).containsExactly(secondJobId)
        assertThat(firstPage.nextCursor).isNotBlank()
        assertThat(secondPage.items.map { it.id }).containsExactly(firstJobId)
        assertThat(secondPage.nextCursor).isNull()

        val chunkPage = embeddingJobStore.findPage(query.copy(chunkId = firstFixture.chunk.id))
        assertThat(chunkPage.items.map { it.id }).containsExactly(firstJobId)
        assertThat(chunkPage.nextCursor).isNull()
    }

    @Test
    fun `active-set backfill keyset batches repair mirrors and enqueue only missing active work`() {
        val activeEmbeddingSetId = ensurePersistedActiveEmbeddingTarget()
        val mirrorFixture = saveFixture("active-backfill-mirror")
        val missingFixture = saveFixture("active-backfill-missing")
        val legacyFixture = saveFixture("active-backfill-legacy")
        val activeEmbeddingId = UUID.fromString(stableUuid("active-backfill-mirror-embedding"))
        val vector = List(384) { index -> if (index == 0) 1f else 0f }.toPgVectorLiteral()

        jdbc.update(
            """
            INSERT INTO chunk_embeddings (
                chunk_embedding_id, chunk_id, embedding_set_id, embedding, metadata, created_at
            ) VALUES (?, ?, ?, CAST(? AS vector), '{}'::jsonb, ?)
            """.trimIndent(),
            activeEmbeddingId,
            UUID.fromString(mirrorFixture.chunk.id.value),
            UUID.fromString(activeEmbeddingSetId.value),
            vector,
            java.sql.Timestamp.from(now),
        )
        val legacySet = embeddingSetStore.resolveOrCreate(
            embeddingSet(
                scope = "active-backfill-legacy",
                projectId = legacyFixture.project.id,
                model = "legacy-backfill-model",
                version = "v1",
            ),
        )
        chunkEmbeddingStore.saveAll(
            listOf(
                ChunkEmbedding(
                    id = ChunkEmbeddingId(stableUuid("active-backfill-legacy-embedding")),
                    embeddingSetId = legacySet.id,
                    chunkId = legacyFixture.chunk.id,
                    embedding = Embedding(listOf(1f, 0f)),
                    createdAt = now,
                ),
            ),
        )

        var afterChunkId: DocumentChunkId? = null
        repeat(3) {
            val result = activeEmbeddingBackfillStore.reconcile(
                embeddingSetId = activeEmbeddingSetId,
                batchSize = 1,
                afterChunkId = afterChunkId,
                enqueuedAt = now,
            )
            assertThat(result.scannedChunks).isEqualTo(1)
            afterChunkId = result.nextChunkId
        }
        val exhausted = activeEmbeddingBackfillStore.reconcile(
            embeddingSetId = activeEmbeddingSetId,
            batchSize = 1,
            afterChunkId = afterChunkId,
            enqueuedAt = now,
        )

        assertThat(exhausted.scannedChunks).isZero()
        assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM chunk_embedding_vectors_384
                WHERE chunk_embedding_id = ?
                """.trimIndent(),
                Long::class.java,
                activeEmbeddingId,
            ),
        ).isEqualTo(1L)
        assertThat(embeddingJobStore.findPage(FindEmbeddingJobsQuery(chunkId = mirrorFixture.chunk.id)).items).isEmpty()
        assertThat(embeddingJobStore.findPage(FindEmbeddingJobsQuery(chunkId = missingFixture.chunk.id)).items)
            .singleElement()
            .extracting { it.embeddingSetId }
            .isEqualTo(activeEmbeddingSetId)
        assertThat(embeddingJobStore.findPage(FindEmbeddingJobsQuery(chunkId = legacyFixture.chunk.id)).items)
            .singleElement()
            .extracting { it.embeddingSetId }
            .isEqualTo(activeEmbeddingSetId)

        val coverage = activeEmbeddingBackfillStore.coverage(activeEmbeddingSetId)
        assertThat(coverage).extracting(
            { it.eligibleTotal },
            { it.pending },
            { it.running },
            { it.retrying },
            { it.succeeded },
            { it.permanentlyFailed },
            { it.missing },
        ).containsExactly(3L, 2L, 0L, 0L, 1L, 0L, 0L)

        val repeated = activeEmbeddingBackfillStore.reconcile(
            embeddingSetId = activeEmbeddingSetId,
            batchSize = 500,
            afterChunkId = null,
            enqueuedAt = now,
        )
        assertThat(repeated.scannedChunks).isZero()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embedding_jobs WHERE embedding_set_id = ?",
                Long::class.java,
                UUID.fromString(activeEmbeddingSetId.value),
            ),
        ).isEqualTo(2L)
    }

    @Test
    fun `concurrent active-set reconciliation creates one job per chunk`() {
        val activeEmbeddingSetId = ensurePersistedActiveEmbeddingTarget()
        repeat(4) { index -> saveFixture("active-backfill-concurrent-$index") }
        val workers = 2
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)

        try {
            val results = (1..workers).map {
                executor.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "concurrent backfill start timed out" }
                    activeEmbeddingBackfillStore.reconcile(
                        embeddingSetId = activeEmbeddingSetId,
                        batchSize = 4,
                        afterChunkId = null,
                        enqueuedAt = now,
                    )
                }
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            results.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }

        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM embedding_jobs WHERE embedding_set_id = ?",
                Long::class.java,
                UUID.fromString(activeEmbeddingSetId.value),
            ),
        ).isEqualTo(4L)
    }

    @Test
    fun `backfill target resolver fails closed for a structurally invalid active pointer`() {
        val mismatchedSetId = UUID.fromString(stableUuid("active-backfill-invalid-pointer"))
        insertServerGlobalEmbeddingSet(
            id = mismatchedSetId,
            fingerprint = "sha256:not-the-fixed-v2-profile",
            manifest = V2EmbeddingProfile.fixed.manifest.canonicalJson,
            embeddingVersion = V2EmbeddingProfile.fixed.revision,
        )
        insertActiveProfilePointer(mismatchedSetId)

        assertThat(structurallyValidActiveEmbeddingSetResolver.findStructurallyValidActiveV2EmbeddingSetId()).isNull()
        assertThat(activeProfilePointer()).isEqualTo(mismatchedSetId.toString())
        assertThat(rowCount("embedding_sets")).isEqualTo(1)
    }

    @Test
    fun `server-global profiles coexist and pointer rollback preserves the exact active set`() {
        val fixture = saveProjectAndIteration("server-global-profile")
        ensurePersistedActiveEmbeddingTarget()
        val document = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "server-global-profile-document",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/server-global-profile.md",
            contentHash = "server-global-profile-document-hash",
        ))
        val storedChunk = writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(DocumentChunkWrite(chunk("server-global-profile-chunk", document, chunkHash = "server-global-profile-chunk-hash"))),
            ),
        ).single()
        val firstSetId = UUID.fromString(stableUuid("server-global-profile-first-set"))
        val secondSetId = UUID.fromString(stableUuid("server-global-profile-second-set"))
        val firstEmbeddingId = UUID.fromString(stableUuid("server-global-profile-first-embedding"))
        val secondEmbeddingId = UUID.fromString(stableUuid("server-global-profile-second-embedding"))
        val vector = List(384) { index -> if (index == 0) 1f else 0f }.toPgVectorLiteral()

        insertServerGlobalEmbeddingSet(firstSetId, "sha256:profile-one")
        insertServerGlobalEmbeddingSet(secondSetId, "sha256:profile-two")
        assertThat(embeddingSetStore.findById(EmbeddingSetId(firstSetId.toString())))
            .extracting(
                { it!!.projectId },
                { it!!.scope },
                { it!!.profileFingerprint },
            )
            .containsExactly(null, EmbeddingSetScope.SERVER_GLOBAL, "sha256:profile-one")
        jdbc.update(
            """
            INSERT INTO chunk_embeddings (
                chunk_embedding_id, chunk_id, embedding_set_id, embedding, metadata, created_at
            ) VALUES (?, ?, ?, CAST(? AS vector), '{}'::jsonb, ?)
            """.trimIndent(),
            firstEmbeddingId,
            UUID.fromString(storedChunk.id.value),
            firstSetId,
            vector,
            java.sql.Timestamp.from(now),
        )
        jdbc.update(
            """
            INSERT INTO chunk_embeddings (
                chunk_embedding_id, chunk_id, embedding_set_id, embedding, metadata, created_at
            ) VALUES (?, ?, ?, CAST(? AS vector), '{}'::jsonb, ?)
            """.trimIndent(),
            secondEmbeddingId,
            UUID.fromString(storedChunk.id.value),
            secondSetId,
            vector,
            java.sql.Timestamp.from(now),
        )
        jdbc.update(
            "INSERT INTO chunk_embedding_vectors_384 (chunk_embedding_id, embedding) VALUES (?, CAST(? AS vector(384)))",
            firstEmbeddingId,
            vector,
        )
        jdbc.update(
            "INSERT INTO chunk_embedding_vectors_384 (chunk_embedding_id, embedding) VALUES (?, CAST(? AS vector(384)))",
            secondEmbeddingId,
            vector,
        )
        jdbc.update(
            "UPDATE embedding_active_profiles SET active_embedding_set_id = ? WHERE scope = 'server_global'",
            firstSetId,
        )
        val legacySet = embeddingSet(
            scope = "server-global-profile-legacy-set",
            projectId = fixture.project.id,
            model = "legacy-profile-test",
            version = "v1",
        )
        embeddingSetStore.resolveOrCreate(legacySet)
        assertThatThrownBy {
            jdbc.update(
                "UPDATE embedding_active_profiles SET active_embedding_set_id = ? WHERE scope = 'server_global'",
                UUID.fromString(legacySet.id.value),
            )
        }.isInstanceOf(Exception::class.java)

        jdbc.dataSource!!.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "UPDATE embedding_active_profiles SET active_embedding_set_id = ? WHERE scope = 'server_global'",
            ).use { statement ->
                statement.setObject(1, secondSetId)
                statement.executeUpdate()
            }
            connection.rollback()
        }

        assertThat(
            jdbc.queryForObject(
                "SELECT active_embedding_set_id::text FROM embedding_active_profiles WHERE scope = 'server_global'",
                String::class.java,
            ),
        ).isEqualTo(firstSetId.toString())
        assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM chunk_embedding_vectors_384 typed_vector
                JOIN chunk_embeddings chunk_embedding
                    ON chunk_embedding.chunk_embedding_id = typed_vector.chunk_embedding_id
                WHERE chunk_embedding.embedding_set_id IN (?, ?)
                """.trimIndent(),
                Long::class.java,
                firstSetId,
                secondSetId,
            ),
        ).isEqualTo(2L)
        assertThatThrownBy {
            jdbc.update("DELETE FROM embedding_sets WHERE embedding_set_id = ?", firstSetId)
        }.isInstanceOf(Exception::class.java)
        assertThatThrownBy {
            jdbc.update(
                "UPDATE embedding_sets SET profile_fingerprint = 'sha256:mutated' WHERE embedding_set_id = ?",
                firstSetId,
            )
        }.isInstanceOf(Exception::class.java)
        assertThatThrownBy {
            insertServerGlobalEmbeddingSet(UUID.fromString(stableUuid("server-global-profile-duplicate")), "sha256:profile-one")
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `V2 migration copies V1 legacy 384 vectors without changing V1 typed rows`() {
        val databaseName = "p2a_legacy_${UUID.randomUUID().toString().replace('-', '_')}"
        val jdbcUrl = postgres.jdbcUrl.substringBeforeLast('/') + "/$databaseName"

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE $databaseName")
            }
        }
        try {
            Flyway.configure()
                .dataSource(jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .target("1")
                .load()
                .migrate()
            seedV1EmbeddingFixture(jdbcUrl)

            Flyway.configure()
                .dataSource(jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT embedding_set.scope, count(*)
                        FROM chunk_embedding_vectors_384 typed_vector
                        JOIN chunk_embeddings chunk_embedding
                            ON chunk_embedding.chunk_embedding_id = typed_vector.chunk_embedding_id
                        JOIN embedding_sets embedding_set
                            ON embedding_set.embedding_set_id = chunk_embedding.embedding_set_id
                        GROUP BY embedding_set.scope
                        """.trimIndent(),
                    ).use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getString("scope")).isEqualTo("LEGACY")
                        assertThat(result.getLong("count")).isEqualTo(1L)
                    }
                    statement.executeQuery("SELECT count(*) FROM chunk_embedding_vectors_2").use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getLong(1)).isEqualTo(1L)
                    }
                    statement.executeQuery(
                        """
                        SELECT profile_fingerprint IS NULL AND profile_manifest IS NULL
                        FROM embedding_sets
                        WHERE scope = 'LEGACY'
                        """.trimIndent(),
                    ).use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getBoolean(1)).isTrue()
                    }
                }
            }
        } finally {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP DATABASE IF EXISTS $databaseName")
                }
            }
        }
    }

    @Test
    fun `artifact graph snapshots replace stale scope data and validate endpoints`() {
        val fixture = saveProjectAndIteration("graph-snapshot")
        val decision = graphNode("graph-snapshot-decision", fixture.project.id, fixture.iteration.id, "decision:ND-1", ArtifactNodeKind.DECISION)
        val task = graphNode("graph-snapshot-task", fixture.project.id, fixture.iteration.id, "task:T-1", ArtifactNodeKind.TASK)
        val staleEvidence = graphNode("graph-snapshot-stale", fixture.project.id, fixture.iteration.id, "evidence:WEB-1", ArtifactNodeKind.EVIDENCE)
        val firstEdge = graphEdge("graph-snapshot-edge-1", fixture.project.id, task.id, decision.id, ArtifactEdgeType.DERIVED_FROM)
        val staleEdge = graphEdge("graph-snapshot-edge-stale", fixture.project.id, task.id, staleEvidence.id, ArtifactEdgeType.EVIDENCED_BY)

        val first = writeUseCase.saveArtifactGraphSnapshot(
            SaveArtifactGraphSnapshotCommand(
                projectId = fixture.project.id,
                iterationId = fixture.iteration.id,
                nodes = listOf(decision, task, staleEvidence),
                edges = listOf(firstEdge, staleEdge),
            ),
        )
        val replacement = writeUseCase.saveArtifactGraphSnapshot(
            SaveArtifactGraphSnapshotCommand(
                projectId = fixture.project.id,
                iterationId = fixture.iteration.id,
                nodes = listOf(decision, task),
                edges = listOf(firstEdge),
            ),
        )

        assertThat(first.nodeCount).isEqualTo(3)
        assertThat(replacement.nodeCount).isEqualTo(2)
        val otherIteration = iterationStore.save(iteration("graph-snapshot-other", fixture.project.id))
        val otherTask = graphNode("graph-snapshot-other-task", fixture.project.id, otherIteration.id, "task:T-other", ArtifactNodeKind.TASK)
        writeUseCase.saveArtifactGraphSnapshot(
            SaveArtifactGraphSnapshotCommand(
                projectId = fixture.project.id,
                iterationId = otherIteration.id,
                nodes = listOf(otherTask),
                edges = emptyList(),
            ),
        )

        assertThat(rowCount("artifact_nodes")).isEqualTo(3)
        assertThat(rowCount("artifact_edges")).isEqualTo(1)
        assertThat(artifactGraphStore.findNodes(GraphNodeSearchQuery(projectId = fixture.project.id, iterationId = fixture.iteration.id)).map { it.naturalKey })
            .containsExactly("decision:ND-1", "task:T-1")
        assertThat(artifactGraphStore.findNodes(GraphNodeSearchQuery(projectId = fixture.project.id)).map { it.naturalKey })
            .contains("decision:ND-1", "task:T-1", "task:T-other")

        assertThatThrownBy {
            writeUseCase.saveArtifactGraphSnapshot(
                SaveArtifactGraphSnapshotCommand(
                    projectId = fixture.project.id,
                    iterationId = fixture.iteration.id,
                    nodes = listOf(task),
                    edges = listOf(firstEdge),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("toNodeId")
    }

    @Test
    fun `artifact graph trace respects direction depth cycle and truncation`() {
        val fixture = saveProjectAndIteration("graph-trace")
        val run = graphNode("graph-trace-run", fixture.project.id, fixture.iteration.id, "run:R-1", ArtifactNodeKind.RUN)
        val task = graphNode("graph-trace-task", fixture.project.id, fixture.iteration.id, "task:T-1", ArtifactNodeKind.TASK)
        val spec = graphNode("graph-trace-spec", fixture.project.id, fixture.iteration.id, "spec_section:impl", ArtifactNodeKind.SPEC_SECTION)
        val decision = graphNode("graph-trace-decision", fixture.project.id, fixture.iteration.id, "decision:ND-1", ArtifactNodeKind.DECISION)
        val cycle = graphNode("graph-trace-cycle", fixture.project.id, fixture.iteration.id, "assumption:A-1", ArtifactNodeKind.ASSUMPTION)
        val edges = listOf(
            graphEdge("graph-trace-run-task", fixture.project.id, run.id, task.id, ArtifactEdgeType.EXECUTED_FOR),
            graphEdge("graph-trace-task-spec", fixture.project.id, task.id, spec.id, ArtifactEdgeType.DERIVED_FROM),
            graphEdge("graph-trace-spec-decision", fixture.project.id, spec.id, decision.id, ArtifactEdgeType.DERIVED_FROM),
            graphEdge("graph-trace-decision-cycle", fixture.project.id, decision.id, cycle.id, ArtifactEdgeType.DEPENDS_ON),
            graphEdge("graph-trace-cycle-task", fixture.project.id, cycle.id, task.id, ArtifactEdgeType.BLOCKS),
        )
        writeUseCase.saveArtifactGraphSnapshot(
            SaveArtifactGraphSnapshotCommand(
                projectId = fixture.project.id,
                iterationId = fixture.iteration.id,
                nodes = listOf(run, task, spec, decision, cycle),
                edges = edges,
            ),
        )

        val upstream = artifactGraphStore.trace(GraphTraceQuery(fixture.project.id, "task:T-1", fixture.iteration.id, GraphTraceDirection.UPSTREAM, maxDepth = 2))
        val shallowUpstream = artifactGraphStore.trace(GraphTraceQuery(fixture.project.id, "task:T-1", fixture.iteration.id, GraphTraceDirection.UPSTREAM, maxDepth = 1))
        val projectWideUpstream = artifactGraphStore.trace(GraphTraceQuery(fixture.project.id, "task:T-1", direction = GraphTraceDirection.UPSTREAM, maxDepth = 2))
        val downstream = artifactGraphStore.trace(GraphTraceQuery(fixture.project.id, "spec_section:impl", fixture.iteration.id, GraphTraceDirection.DOWNSTREAM, maxDepth = 2))

        assertThat(upstream.nodes.map { it.node.naturalKey }).contains("task:T-1", "spec_section:impl", "decision:ND-1")
        assertThat(upstream.nodes.map { it.node.naturalKey }).doesNotContain("assumption:A-1")
        assertThat(upstream.truncated).isTrue()
        assertThat(shallowUpstream.nodes.map { it.node.naturalKey }).doesNotContain("decision:ND-1")
        assertThat(shallowUpstream.truncated).isTrue()
        assertThat(projectWideUpstream.root.naturalKey).isEqualTo("task:T-1")
        assertThat(downstream.nodes.map { it.node.naturalKey }).contains("spec_section:impl", "task:T-1", "run:R-1")
    }

    @Test
    fun `storage adapters persist project iteration document graph task run and chunk records`() {
        val fixture = saveFixture("adapter-persistence")
        val remappedProject = projectStore.save(project("adapter-persistence-remap").copy(
            sourceProjectId = fixture.project.sourceProjectId,
        ))

        assertThat(projectStore.findById(fixture.project.id)).isEqualTo(remappedProject)
        assertThat(remappedProject.id).isEqualTo(fixture.project.id)
        assertThat(iterationStore.findById(fixture.iteration.id)).isEqualTo(fixture.iteration)
        assertThat(documentSnapshotStore.findById(fixture.document.id)).isEqualTo(fixture.document)
        assertThat(taskGraphStore.findById(fixture.taskGraph.id)).isEqualTo(fixture.taskGraph)
        assertThat(taskStore.findById(fixture.task.id)).isEqualTo(fixture.task)
        assertThat(runRecordStore.findById(fixture.run.id)).isEqualTo(fixture.run)
        assertThat(documentChunkStore.findByDocumentId(fixture.document.id)).containsExactly(fixture.chunk)
        assertThat(runRecordStore.findByTaskId(fixture.task.id)).containsExactly(fixture.run)
        assertThat(taskStore.findByGraphId(fixture.taskGraph.id)).containsExactly(fixture.task)
        assertThat(taskGraphDocumentId(fixture.taskGraph.id)).isEqualTo(fixture.document.id.value)
        assertThat(
            artifactQuery.findArtifacts(
                FindArtifactsQuery(
                    projectId = fixture.project.id,
                    iterationId = fixture.iteration.id,
                    artifactType = ArtifactType.TASK_GRAPH,
                ),
            ).items.single().sourcePath,
        ).isEqualTo(fixture.document.sourcePath)
    }

    @Test
    fun `document snapshots and chunks are idempotent by content and chunk hash`() {
        val fixture = saveProjectAndIteration("idempotency")
        val activeEmbeddingSetId = ensurePersistedActiveEmbeddingTarget()
        val first = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "idempotency-first",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/spec.md",
            contentHash = "doc-hash-a",
            content = "same content",
        ))
        val repeated = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "idempotency-repeat",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/spec.md",
            contentHash = "doc-hash-a",
            content = "same content",
        ))
        val changed = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "idempotency-changed",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/spec.md",
            contentHash = "doc-hash-b",
            content = "changed content",
        ))

        assertThat(repeated.id).isEqualTo(first.id)
        assertThat(changed.snapshotVersion).isEqualTo(2)
        assertThat(rowCount("documents")).isEqualTo(2)

        val firstChunk = writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = first.id,
                chunks = listOf(DocumentChunkWrite(chunk("idempotency-chunk-a", first, chunkHash = "chunk-hash-a"))),
            ),
        ).single()
        val repeatedChunk = writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = first.id,
                chunks = listOf(DocumentChunkWrite(chunk("idempotency-chunk-repeat", first, chunkHash = "chunk-hash-a"))),
            ),
        ).single()

        assertThat(repeatedChunk.id).isEqualTo(firstChunk.id)
        assertThat(rowCount("document_chunks")).isEqualTo(1)
        assertThat(embeddingJobStore.findPage(FindEmbeddingJobsQuery(chunkId = firstChunk.id)).items)
            .singleElement()
            .extracting { it.embeddingSetId }
            .isEqualTo(activeEmbeddingSetId)
    }

    @Test
    fun `embedding persistence is idempotent by chunk and embedding set`() {
        val fixture = saveProjectAndIteration("embedding")
        ensurePersistedActiveEmbeddingTarget()
        val document = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "embedding-doc",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/embedding.md",
            contentHash = "embedding-doc-hash",
        ))
        val firstChunk = chunk("embedding-chunk-a", document, chunkHash = "embedding-chunk-hash")
        val firstSet = embeddingSet("embedding-set-a", fixture.project.id, model = "text-embedding-test", version = "v1")
        val secondSet = embeddingSet("embedding-set-b", fixture.project.id, model = "text-embedding-test", version = "v2")

        writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(
                    DocumentChunkWrite(
                        chunk = firstChunk,
                        embeddingSet = firstSet,
                        embedding = Embedding(listOf(0.1f, 0.2f)),
                        embeddingHash = ContentHash("embedding-hash-a"),
                    ),
                ),
            ),
        )
        writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(
                    DocumentChunkWrite(
                        chunk = chunk("embedding-chunk-repeat", document, chunkHash = firstChunk.chunkHash.value),
                        embeddingSet = firstSet,
                        embedding = Embedding(listOf(0.1f, 0.2f)),
                        embeddingHash = ContentHash("embedding-hash-a"),
                    ),
                ),
            ),
        )
        writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(
                    DocumentChunkWrite(
                        chunk = chunk("embedding-chunk-second-set", document, chunkHash = firstChunk.chunkHash.value),
                        embeddingSet = secondSet,
                        embedding = Embedding(listOf(0.3f, 0.4f)),
                        embeddingHash = ContentHash("embedding-hash-b"),
                    ),
                ),
            ),
        )

        val savedChunk = documentChunkStore.findByDocumentId(document.id).single()
        assertThat(chunkEmbeddingStore.findByChunkId(savedChunk.id).map { it.embeddingSetId })
            .containsExactlyInAnyOrder(firstSet.id, secondSet.id)
        assertThat(rowCount("document_chunks")).isEqualTo(1)
        assertThat(rowCount("chunk_embeddings")).isEqualTo(2)
        assertThat(rowCount("chunk_embedding_vectors_2")).isEqualTo(2)
        assertThat(rowCount("chunk_embedding_vectors_1536")).isEqualTo(0)

        assertThatThrownBy {
            writeUseCase.saveDocumentChunks(
                SaveDocumentChunksCommand(
                    documentId = document.id,
                    chunks = listOf(
                        DocumentChunkWrite(
                            chunk = chunk("embedding-chunk-conflict", document, chunkHash = firstChunk.chunkHash.value),
                            embeddingSet = firstSet,
                            embedding = Embedding(listOf(0.9f, 0.8f)),
                            embeddingHash = ContentHash("embedding-hash-conflict"),
                        ),
                    ),
                ),
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("different embedding hash or vector")
    }

    @Test
    fun `384 dimensional embeddings are persisted through the typed vector mirror`() {
        val fixture = saveProjectAndIteration("typed-384")
        ensurePersistedActiveEmbeddingTarget()
        val document = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "typed-384-document",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/typed-384.md",
            contentHash = "typed-384-document-hash",
        ))
        val embeddingSet = embeddingSet(
            scope = "typed-384-set",
            projectId = fixture.project.id,
            model = "legacy-384",
            version = "v1",
            dimension = 384,
        )

        writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(
                    DocumentChunkWrite(
                        chunk = chunk("typed-384-chunk", document, chunkHash = "typed-384-chunk-hash"),
                        embeddingSet = embeddingSet,
                        embedding = Embedding(List(384) { index -> if (index == 0) 1f else 0f }),
                    ),
                ),
            ),
        )

        assertThat(rowCount("chunk_embedding_vectors_384")).isEqualTo(1)
    }

    @Test
    fun `write use case normalizes source paths while preserving raw paths`() {
        val fixture = saveProjectAndIteration("path-normalization")
        val rawPath = """iterations\v1\gate-b-spec\spec.json"""
        val normalizedPath = "iterations/v1/gate-b-spec/spec.json"
        val first = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "path-normalization-first",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = rawPath,
            rawSourcePath = rawPath,
            contentHash = "path-normalized-hash",
        ))
        val repeated = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "path-normalization-repeat",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = normalizedPath,
            rawSourcePath = normalizedPath,
            contentHash = "path-normalized-hash",
        ))

        assertThat(first.sourcePath).isEqualTo(normalizedPath)
        assertThat(repeated.id).isEqualTo(first.id)
        assertThat(rowCount("documents")).isEqualTo(1)
        assertThat(documentPathRow(first.id)).containsEntry("source_path", normalizedPath)
        assertThat(documentPathRow(first.id)).containsEntry("raw_source_path", rawPath)

        val found = artifactQuery.findArtifacts(
            FindArtifactsQuery(
                projectId = fixture.project.id,
                iterationId = fixture.iteration.id,
                artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                sourcePath = normalizedPath,
            ),
        )
        assertThat(found.items.map { it.artifactId }).containsExactly(first.id.value)
    }

    @Test
    fun `empty profile state bootstraps one pending job while no provider is configured`() {
        val fixture = saveProjectAndIteration("pending-job-not-configured")
        val document = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "pending-job-not-configured-document",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/pending-job-not-configured.md",
            contentHash = "pending-job-not-configured-document-hash",
        ))

        assertThat(embeddingPort.providerState).isEqualTo(EmbeddingProviderState.NOT_CONFIGURED)
        assertThat(activeProfilePointer()).isNull()
        assertThat(rowCount("embedding_sets")).isZero()

        val first = writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(DocumentChunkWrite(chunk(
                    "pending-job-not-configured-first",
                    document,
                    chunkHash = "pending-job-not-configured-chunk-hash",
                ))),
            ),
        ).single()
        val repeated = writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(DocumentChunkWrite(chunk(
                    "pending-job-not-configured-repeat",
                    document,
                    chunkHash = "pending-job-not-configured-chunk-hash",
                ))),
            ),
        ).single()

        assertThat(repeated.id).isEqualTo(first.id)
        val activeEmbeddingSetId = persistedActiveEmbeddingSetResolver.requirePersistedActiveV2EmbeddingSetId()
        assertThat(embeddingJobStore.findPage(FindEmbeddingJobsQuery(chunkId = first.id)).items)
            .singleElement()
            .extracting(
                { it.chunkId },
                { it.embeddingSetId },
                { it.status },
            )
            .containsExactly(first.id, activeEmbeddingSetId, EmbeddingJobStatus.PENDING)
        assertThat(activeProfilePointer()).isEqualTo(activeEmbeddingSetId.value)
        assertThat(rowCount("embedding_sets")).isEqualTo(1)
    }

    @Test
    fun `chunk and pending job roll back together when enqueue fails`() {
        val fixture = saveProjectAndIteration("chunk-job-atomicity")
        val document = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "chunk-job-atomicity-document",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/chunk-job-atomicity.md",
            contentHash = "chunk-job-atomicity-document-hash",
        ))
        jdbc.execute(
            """
            CREATE FUNCTION reject_embedding_job_enqueue()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
                RAISE EXCEPTION 'embedding job enqueue rejected for atomicity test';
            END;
            $$
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TRIGGER reject_embedding_job_enqueue_trigger
            BEFORE INSERT ON embedding_jobs
            FOR EACH ROW
            EXECUTE FUNCTION reject_embedding_job_enqueue()
            """.trimIndent(),
        )

        try {
            assertThatThrownBy {
                writeUseCase.saveDocumentChunks(
                    SaveDocumentChunksCommand(
                        documentId = document.id,
                        chunks = listOf(DocumentChunkWrite(chunk(
                            "chunk-job-atomicity",
                            document,
                            chunkHash = "chunk-job-atomicity-chunk-hash",
                        ))),
                    ),
                )
            }.isInstanceOf(DataAccessException::class.java)
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_embedding_job_enqueue_trigger ON embedding_jobs")
            jdbc.execute("DROP FUNCTION IF EXISTS reject_embedding_job_enqueue()")
        }

        assertThat(rowCount("document_chunks")).isZero()
        assertThat(rowCount("embedding_jobs")).isZero()
        assertThat(rowCount("embedding_active_profiles")).isZero()
        assertThat(rowCount("embedding_sets")).isZero()
    }

    @Test
    fun `profile mismatch with a structurally valid pointer still queues without profile mutation`() {
        val mismatchedSetId = UUID.randomUUID()
        insertServerGlobalEmbeddingSet(
            id = mismatchedSetId,
            fingerprint = "sha256:provider-profile-mismatch",
            manifest = V2EmbeddingProfile.fixed.manifest.canonicalJson,
            embeddingVersion = V2EmbeddingProfile.fixed.revision,
        )
        insertActiveProfilePointer(mismatchedSetId)
        val fixture = saveProjectAndIteration("profile-mismatch-queue")
        val document = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "profile-mismatch-queue-document",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/profile-mismatch-queue.md",
            contentHash = "profile-mismatch-queue-document-hash",
        ))
        val pointerBefore = activeProfilePointer()
        val setCountBefore = rowCount("embedding_sets")

        assertThat(embeddingPort.providerState).isEqualTo(EmbeddingProviderState.NOT_CONFIGURED)
        assertThatThrownBy { activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId() }
            .isInstanceOf(ActiveEmbeddingProfileResolutionException::class.java)

        val saved = writeUseCase.saveDocumentChunks(
            SaveDocumentChunksCommand(
                documentId = document.id,
                chunks = listOf(DocumentChunkWrite(chunk(
                    "profile-mismatch-queue",
                    document,
                    chunkHash = "profile-mismatch-queue-chunk-hash",
                ))),
            ),
        ).single()

        assertThat(persistedActiveEmbeddingSetResolver.requirePersistedActiveV2EmbeddingSetId())
            .isEqualTo(EmbeddingSetId(mismatchedSetId.toString()))
        assertThat(embeddingJobStore.findPage(FindEmbeddingJobsQuery(chunkId = saved.id)).items)
            .singleElement()
            .extracting(
                { it.embeddingSetId },
                { it.status },
            )
            .containsExactly(EmbeddingSetId(mismatchedSetId.toString()), EmbeddingJobStatus.PENDING)
        assertThat(activeProfilePointer()).isEqualTo(pointerBefore)
        assertThat(rowCount("embedding_sets")).isEqualTo(setCountBefore)
        assertThat(rowCount("chunk_embeddings")).isZero()
        assertThat(rowCount("chunk_embedding_vectors_384")).isZero()
    }

    @Test
    fun `pointerless server-global set fails closed before chunk or job persistence`() {
        val partialSetId = UUID.randomUUID()
        insertServerGlobalEmbeddingSet(
            id = partialSetId,
            fingerprint = V2EmbeddingProfile.fixed.fingerprint,
            manifest = V2EmbeddingProfile.fixed.manifest.canonicalJson,
            embeddingVersion = V2EmbeddingProfile.fixed.revision,
        )
        val fixture = saveProjectAndIteration("absent-pointer-queue")
        val document = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "absent-pointer-queue-document",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/absent-pointer-queue.md",
            contentHash = "absent-pointer-queue-document-hash",
        ))

        assertThatThrownBy {
            writeUseCase.saveDocumentChunks(
                SaveDocumentChunksCommand(
                    documentId = document.id,
                    chunks = listOf(DocumentChunkWrite(chunk(
                        "absent-pointer-queue",
                        document,
                        chunkHash = "absent-pointer-queue-chunk-hash",
                    ))),
                ),
            )
        }.isInstanceOf(PersistedActiveEmbeddingSetResolutionException::class.java)

        assertThat(rowCount("embedding_active_profiles")).isZero()
        assertThat(rowCount("embedding_sets")).isEqualTo(1)
        assertThat(rowCount("document_chunks")).isZero()
        assertThat(rowCount("embedding_jobs")).isZero()
    }

    @Test
    fun `dangling active pointer fails closed before chunk or job persistence`() {
        val missingSetId = UUID.randomUUID()
        insertDanglingActiveProfilePointer(missingSetId)
        val fixture = saveProjectAndIteration("dangling-pointer-queue")
        val document = writeUseCase.saveDocumentSnapshot(documentCommand(
            scope = "dangling-pointer-queue-document",
            projectId = fixture.project.id,
            iterationId = fixture.iteration.id,
            sourcePath = "docs/dangling-pointer-queue.md",
            contentHash = "dangling-pointer-queue-document-hash",
        ))

        assertThatThrownBy {
            writeUseCase.saveDocumentChunks(
                SaveDocumentChunksCommand(
                    documentId = document.id,
                    chunks = listOf(DocumentChunkWrite(chunk(
                        "dangling-pointer-queue",
                        document,
                        chunkHash = "dangling-pointer-queue-chunk-hash",
                    ))),
                ),
            )
        }.isInstanceOf(PersistedActiveEmbeddingSetResolutionException::class.java)

        assertThat(activeProfilePointer()).isEqualTo(missingSetId.toString())
        assertThat(rowCount("document_chunks")).isZero()
        assertThat(rowCount("embedding_jobs")).isZero()
    }

    @Test
    fun `active V2 profile resolver atomically bootstraps one immutable global set and pointer`() {
        val resolvedId = activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId()
        val profile = V2EmbeddingProfile.fixed
        val stored = requireNotNull(embeddingSetStore.findById(resolvedId))

        assertThat(stored).extracting(
            { it.projectId },
            { it.scope },
            { it.profileFingerprint },
            { it.embeddingModel },
            { it.embeddingDimension },
            { it.embeddingVersion },
            { it.distanceMetric },
            { it.storageType },
        ).containsExactly(
            null,
            EmbeddingSetScope.SERVER_GLOBAL,
            profile.fingerprint,
            profile.model,
            profile.dimension,
            profile.revision,
            profile.distanceMetric,
            EmbeddingStorageType.VECTOR_INDEX,
        )
        assertThat(profileManifestMatches(resolvedId, profile.manifest.canonicalJson)).isTrue()
        assertThat(activeProfilePointer()).isEqualTo(resolvedId.value)
        assertThat(serverGlobalSetCount()).isEqualTo(1)
    }

    @Test
    fun `active V2 profile resolver follows the exact existing pointer without model tuple lookup`() {
        val initialId = activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId()
        val profile = V2EmbeddingProfile.fixed
        val fixture = saveFixture("active-v2-exact-resolution")
        val legacySet = embeddingSetStore.resolveOrCreate(
            embeddingSet(
                scope = "active-v2-exact-resolution-legacy",
                projectId = fixture.project.id,
                model = profile.model,
                version = profile.revision,
                dimension = profile.dimension,
            ),
        )

        assertThat(activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId()).isEqualTo(initialId)
        assertThat(activeProfilePointer()).isEqualTo(initialId.value)
        assertThat(legacySet.id).isNotEqualTo(initialId)
        assertThat(serverGlobalSetCount()).isEqualTo(1)
    }

    @Test
    fun `active V2 profile resolver leaves a mismatched existing pointer untouched`() {
        val mismatchedSetId = UUID.randomUUID()
        insertServerGlobalEmbeddingSet(
            id = mismatchedSetId,
            fingerprint = "sha256:mismatched-profile",
            manifest = V2EmbeddingProfile.fixed.manifest.canonicalJson,
            embeddingVersion = V2EmbeddingProfile.fixed.revision,
        )
        insertActiveProfilePointer(mismatchedSetId)
        val pointerBefore = activeProfilePointer()
        val setCountBefore = rowCount("embedding_sets")

        assertThatThrownBy { activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId() }
            .isInstanceOf(ActiveEmbeddingProfileResolutionException::class.java)

        assertThat(activeProfilePointer()).isEqualTo(pointerBefore)
        assertThat(rowCount("embedding_sets")).isEqualTo(setCountBefore)
        assertThat(serverGlobalSetCount()).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT profile_fingerprint FROM embedding_sets WHERE embedding_set_id = ?",
                String::class.java,
                mismatchedSetId,
            ),
        ).isEqualTo("sha256:mismatched-profile")
    }

    @Test
    fun `active V2 profile resolver does not repair a server global set without a pointer`() {
        val existingSetId = UUID.randomUUID()
        insertServerGlobalEmbeddingSet(
            id = existingSetId,
            fingerprint = V2EmbeddingProfile.fixed.fingerprint,
            manifest = V2EmbeddingProfile.fixed.manifest.canonicalJson,
            embeddingVersion = V2EmbeddingProfile.fixed.revision,
        )
        val setCountBefore = rowCount("embedding_sets")

        assertThatThrownBy { activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId() }
            .isInstanceOf(ActiveEmbeddingProfileResolutionException::class.java)

        assertThat(activeProfilePointer()).isNull()
        assertThat(rowCount("embedding_sets")).isEqualTo(setCountBefore)
        assertThat(serverGlobalSetCount()).isEqualTo(1)
    }

    @Test
    fun `active V2 profile resolver leaves dangling pointer untouched`() {
        val missingSetId = UUID.randomUUID()
        insertDanglingActiveProfilePointer(missingSetId)
        val pointerBefore = activeProfilePointer()

        assertThatThrownBy { activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId() }
            .isInstanceOf(ActiveEmbeddingProfileResolutionException::class.java)

        assertThat(activeProfilePointer()).isEqualTo(pointerBefore)
        assertThat(rowCount("embedding_sets")).isZero()
        assertThat(rowCount("embedding_active_profiles")).isEqualTo(1)
    }

    @Test
    fun `active V2 profile resolver leaves malformed profile manifest untouched`() {
        val malformedSetId = UUID.randomUUID()
        insertServerGlobalEmbeddingSet(
            id = malformedSetId,
            fingerprint = V2EmbeddingProfile.fixed.fingerprint,
            manifest = """{"profileSchema":"p2a.embedding-profile.v1","unexpected":true}""",
            embeddingVersion = V2EmbeddingProfile.fixed.revision,
        )
        insertActiveProfilePointer(malformedSetId)
        val pointerBefore = activeProfilePointer()
        val manifestBefore = jdbc.queryForObject(
            "SELECT profile_manifest::text FROM embedding_sets WHERE embedding_set_id = ?",
            String::class.java,
            malformedSetId,
        )

        assertThatThrownBy { activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId() }
            .isInstanceOf(ActiveEmbeddingProfileResolutionException::class.java)

        assertThat(activeProfilePointer()).isEqualTo(pointerBefore)
        assertThat(
            jdbc.queryForObject(
                "SELECT profile_manifest::text FROM embedding_sets WHERE embedding_set_id = ?",
                String::class.java,
                malformedSetId,
            ),
        ).isEqualTo(manifestBefore)
        assertThat(serverGlobalSetCount()).isEqualTo(1)
    }

    @Test
    fun `active V2 profile bootstrap is concurrent and restart safe`() {
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val resolutions = (1..workers).map {
                executor.submit<EmbeddingSetId> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId()
                }
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            val resolvedIds = resolutions.map { it.get(20, TimeUnit.SECONDS) }
            assertThat(resolvedIds).containsOnly(resolvedIds.first())
            repeat(3) {
                assertThat(activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId()).isEqualTo(resolvedIds.first())
            }
            assertThat(serverGlobalSetCount()).isEqualTo(1)
            assertThat(rowCount("embedding_active_profiles")).isEqualTo(1)
            assertThat(activeProfilePointer()).isEqualTo(resolvedIds.first().value)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `active V2 profile bootstrap preserves legacy vectors`() {
        val fixture = saveFixture("active-v2-legacy-preservation")
        val legacySet = embeddingSetStore.resolveOrCreate(
            embeddingSet(
                scope = "active-v2-legacy-preservation",
                projectId = fixture.project.id,
                model = "legacy-vector-model",
                version = "v1",
                dimension = 384,
            ),
        )
        val legacyEmbeddingId = UUID.randomUUID()
        val legacyVector = List(384) { index -> if (index == 0) 1f else 0f }.toPgVectorLiteral()
        jdbc.update(
            """
            INSERT INTO chunk_embeddings (
                chunk_embedding_id, chunk_id, embedding_set_id, embedding, metadata, created_at
            ) VALUES (?, ?, ?, CAST(? AS vector), '{}'::jsonb, ?)
            """.trimIndent(),
            legacyEmbeddingId,
            UUID.fromString(fixture.chunk.id.value),
            UUID.fromString(legacySet.id.value),
            legacyVector,
            java.sql.Timestamp.from(now),
        )
        jdbc.update(
            "INSERT INTO chunk_embedding_vectors_384 (chunk_embedding_id, embedding) VALUES (?, CAST(? AS vector(384)))",
            legacyEmbeddingId,
            legacyVector,
        )

        val resolvedId = activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId()

        assertThat(resolvedId).isNotEqualTo(legacySet.id)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM chunk_embeddings WHERE embedding_set_id = ?",
                Long::class.java,
                UUID.fromString(legacySet.id.value),
            ),
        ).isEqualTo(1L)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM chunk_embedding_vectors_384 WHERE chunk_embedding_id = ?",
                Long::class.java,
                legacyEmbeddingId,
            ),
        ).isEqualTo(1L)
        assertThat(serverGlobalSetCount()).isEqualTo(1)
    }

    private fun saveFixture(scope: String): StoredFixture {
        val base = saveProjectAndIteration(scope)
        val document = documentSnapshotStore.save(document(scope, base.project.id, base.iteration.id))
        val taskGraph = taskGraphStore.save(taskGraph(scope, base.project.id, base.iteration.id, document))
        val task = taskStore.saveAll(listOf(task(scope, base.project.id, base.iteration.id, taskGraph.id))).single()
        val run = runRecordStore.save(runRecord(scope, base.project.id, base.iteration.id, task.id, document))
        val chunk = documentChunkStore.saveAll(listOf(chunk(scope, document, task.id, run.id))).single()
        return StoredFixture(base.project, base.iteration, document, taskGraph, task, run, chunk)
    }

    private fun saveProjectAndIteration(scope: String): ProjectIterationFixture {
        val project = projectStore.save(project(scope))
        val iteration = iterationStore.save(iteration(scope, project.id))
        return ProjectIterationFixture(project, iteration)
    }

    private fun ensurePersistedActiveEmbeddingTarget(): EmbeddingSetId =
        activeEmbeddingProfileResolver.resolveActiveV2EmbeddingSetId()

    private fun newEmbeddingWorker(
        embeddingPort: FakeEmbeddingPort,
        executor: java.util.concurrent.ExecutorService,
        properties: EmbeddingWorkerProperties = EmbeddingWorkerProperties(),
        clock: Clock = Clock.systemUTC(),
    ): EmbeddingJobWorker =
        EmbeddingJobWorker(
            embeddingPort = embeddingPort,
            activeEmbeddingProfileResolver = activeEmbeddingProfileResolver,
            documentChunkStore = documentChunkStore,
            embeddingJobStore = embeddingJobStore,
            completionService = EmbeddingJobCompletionService(
                chunkEmbeddingStore = chunkEmbeddingStore,
                embeddingJobStore = embeddingJobStore,
                transactionManager = transactionManager,
                clock = clock,
            ),
            properties = properties,
            workerExecutor = executor,
            clock = clock,
        )

    private fun makeEmbeddingJobDue(jobId: EmbeddingJobId) {
        jdbc.update(
            "UPDATE embedding_jobs SET next_attempt_at = now() - INTERVAL '1 second' WHERE embedding_job_id = ?",
            UUID.fromString(jobId.value),
        )
    }

    private fun tableNames(): Set<String> =
        strings(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_type = 'BASE TABLE'
            """.trimIndent(),
        ).toSet()

    private fun columnNames(tableName: String): Set<String> =
        jdbc.query(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("column_name") },
            tableName,
        ).toSet()

    private fun indexAndConstraintNames(): Set<String> =
        (
            strings("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'") +
                strings(
                    """
                    SELECT constraint_name
                    FROM information_schema.table_constraints
                    WHERE table_schema = 'public'
                    """.trimIndent(),
                )
            ).toSet()

    private fun constraintDefinition(constraintName: String): String =
        jdbc.queryForObject(
            """
            SELECT pg_get_constraintdef(constraint_row.oid)
            FROM pg_constraint AS constraint_row
            JOIN pg_namespace AS namespace ON namespace.oid = constraint_row.connamespace
            WHERE namespace.nspname = 'public'
              AND constraint_row.conname = ?
            """.trimIndent(),
            String::class.java,
            constraintName,
        )!!

    private fun strings(sql: String): List<String> =
        jdbc.query(sql) { rs, _ -> rs.getString(1) }

    private fun rowCount(table: String): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java) ?: 0L

    private fun activeProfilePointer(): String? =
        jdbc.query(
            "SELECT active_embedding_set_id::text FROM embedding_active_profiles WHERE scope = 'server_global'",
        ) { row, _ -> row.getString(1) }.singleOrNull()

    private fun serverGlobalSetCount(): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM embedding_sets WHERE scope = 'SERVER_GLOBAL'",
            Long::class.java,
        ) ?: 0L

    private fun profileManifestMatches(embeddingSetId: EmbeddingSetId, expectedManifest: String): Boolean =
        jdbc.queryForObject(
            """
            SELECT profile_manifest = CAST(? AS jsonb)
            FROM embedding_sets
            WHERE embedding_set_id = ?
            """.trimIndent(),
            Boolean::class.java,
            expectedManifest,
            UUID.fromString(embeddingSetId.value),
        ) ?: false

    private fun insertActiveProfilePointer(embeddingSetId: UUID) {
        jdbc.update(
            "INSERT INTO embedding_active_profiles (scope, active_embedding_set_id) VALUES ('server_global', ?)",
            embeddingSetId,
        )
    }

    private fun insertDanglingActiveProfilePointer(embeddingSetId: UUID) {
        jdbc.execute("ALTER TABLE embedding_active_profiles DISABLE TRIGGER ALL")
        try {
            insertActiveProfilePointer(embeddingSetId)
        } finally {
            jdbc.execute("ALTER TABLE embedding_active_profiles ENABLE TRIGGER ALL")
        }
    }

    private fun documentPathRow(documentId: DocumentId): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT source_path, raw_source_path FROM documents WHERE document_id = ?",
            UUID.fromString(documentId.value),
        )

    private fun taskGraphDocumentId(taskGraphId: TaskGraphId): String? =
        jdbc.queryForObject(
            "SELECT document_id::text FROM task_graphs WHERE task_graph_id = ?",
            String::class.java,
            UUID.fromString(taskGraphId.value),
        )

    private fun insertServerGlobalEmbeddingSet(
        id: UUID,
        fingerprint: String,
        manifest: String = """{"schemaVersion":"p2a.embedding-profile.v1"}""",
        embeddingModel: String = "intfloat/multilingual-e5-small",
        embeddingDimension: Int = 384,
        embeddingVersion: String = "v2",
        distanceMetric: String = "cosine",
        storageType: String = "vector",
    ) {
        jdbc.update(
            """
            INSERT INTO embedding_sets (
                embedding_set_id, project_id, scope, embedding_model, embedding_dimension,
                embedding_version, distance_metric, storage_type, profile_fingerprint,
                profile_manifest, metadata, created_at
            ) VALUES (
                ?, NULL, 'SERVER_GLOBAL', ?, ?,
                ?, ?, ?, ?, CAST(? AS jsonb), '{}'::jsonb, ?
            )
            """.trimIndent(),
            id,
            embeddingModel,
            embeddingDimension,
            embeddingVersion,
            distanceMetric,
            storageType,
            fingerprint,
            manifest,
            java.sql.Timestamp.from(now),
        )
    }

    companion object {
        private val pgvectorImage = DockerImageName.parse("pgvector/pgvector:0.8.5-pg17-bookworm@sha256:d2ef61f42ef767baa5a1475393303cc235bcd92febd9d7014eddb48b41f3bad0")
            .asCompatibleSubstituteFor("postgres")

        @JvmStatic
        val postgres: PgVectorContainer = PgVectorContainer(pgvectorImage)
            .withDatabaseName("p2a_memory_test")
            .withUsername("p2a")
            .withPassword("p2a")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            if (!postgres.isRunning) {
                postgres.start()
            }
            waitUntilJdbcReachable()
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }

        @AfterAll
        @JvmStatic
        fun stopPostgres() {
            postgres.stop()
        }

        private fun waitUntilJdbcReachable() {
            val deadline = System.nanoTime() + 30_000_000_000L
            var lastFailure: Exception? = null
            while (System.nanoTime() < deadline) {
                try {
                    DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { return }
                } catch (failure: Exception) {
                    lastFailure = failure
                    Thread.sleep(200)
                }
            }
            throw IllegalStateException("PostgreSQL Testcontainer JDBC URL was not reachable", lastFailure)
        }
    }
}

private data class ProjectIterationFixture(
    val project: Project,
    val iteration: Iteration,
)

private data class StoredFixture(
    val project: Project,
    val iteration: Iteration,
    val document: DocumentSnapshot,
    val taskGraph: TaskGraph,
    val task: Task,
    val run: RunRecord,
    val chunk: DocumentChunk,
)

class PgVectorContainer(imageName: DockerImageName) : PostgreSQLContainer<PgVectorContainer>(imageName)

private fun project(scope: String): Project {
    val id = ProjectId(stableUuid("$scope-project"))
    return Project(
        id = id,
        sourceProjectId = SourceProjectId("source-project-$scope"),
        name = "Project $scope",
        canonicalServerId = CanonicalServerId(id.value),
        rootPath = "/repo/$scope",
        sourceReference = sourceReference(id.value, "projects/$scope"),
        createdAt = now,
    )
}

private fun iteration(scope: String, projectId: ProjectId): Iteration {
    val id = IterationId(stableUuid("$scope-iteration"))
    return Iteration(
        id = id,
        projectId = projectId,
        sourceIterationId = SourceIterationId("source-iteration-$scope"),
        label = "Iteration $scope",
        status = IterationStatus.ACTIVE,
        sourceReference = sourceReference(id.value, "iterations/$scope"),
        createdAt = now,
    )
}

private fun document(scope: String, projectId: ProjectId, iterationId: IterationId): DocumentSnapshot {
    val id = DocumentId(stableUuid("$scope-document"))
    return DocumentSnapshot(
        id = id,
        projectId = projectId,
        iterationId = iterationId,
        sourceDocumentId = SourceDocumentId("source-document-$scope"),
        sourcePath = "docs/$scope.md",
        snapshotVersion = 1,
        artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
        title = "Document $scope",
        content = "Document content for $scope",
        contentHash = ContentHash("document-hash-$scope"),
        sourceReference = sourceReference(id.value, "docs/$scope.md"),
        capturedAt = now,
        createdAt = now,
    )
}

private fun taskGraph(
    scope: String,
    projectId: ProjectId,
    iterationId: IterationId,
    document: DocumentSnapshot,
): TaskGraph {
    val id = TaskGraphId(stableUuid("$scope-task-graph"))
    val taskId = TaskId(stableUuid("$scope-task"))
    return TaskGraph(
        id = id,
        projectId = projectId,
        iterationId = iterationId,
        sourceTaskGraphId = SourceTaskGraphId("source-task-graph-$scope"),
        sourceDocumentId = document.sourceDocumentId,
        graphHash = ContentHash("task-graph-hash-$scope"),
        graphJson = """{"tasks":["${taskId.value}"]}""",
        taskIds = setOf(taskId),
        sourceReference = sourceReference(id.value, "task-graphs/$scope.json"),
        createdAt = now,
    )
}

private fun task(scope: String, projectId: ProjectId, iterationId: IterationId, taskGraphId: TaskGraphId): Task {
    val id = TaskId(stableUuid("$scope-task"))
    return Task(
        id = id,
        projectId = projectId,
        iterationId = iterationId,
        taskGraphId = taskGraphId,
        sourceTaskId = SourceTaskId("source-task-$scope"),
        title = "Task $scope",
        description = "Task description for $scope",
        status = TaskStatus.READY,
        targetArea = "integration-tests",
        dependencies = emptySet(),
        acceptanceCriteria = listOf("Persist task $scope"),
        sourceReference = sourceReference(id.value, "task-graphs/$scope.json#task"),
        createdAt = now,
    )
}

private fun runRecord(
    scope: String,
    projectId: ProjectId,
    iterationId: IterationId,
    taskId: TaskId,
    document: DocumentSnapshot,
): RunRecord {
    val id = RunId(stableUuid("$scope-run"))
    return RunRecord(
        id = id,
        projectId = projectId,
        iterationId = iterationId,
        taskId = taskId,
        sourceRunId = SourceRunId("source-run-$scope"),
        status = RunStatus.FINISHED,
        agentTool = "codex",
        runJson = """{"status":"finished"}""",
        artifactRefs = listOf(
            ArtifactRef(
                artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
                artifactId = document.id.value,
                sourcePath = document.sourcePath,
            ),
        ),
        sourceReference = sourceReference(id.value, "runs/$scope.json"),
        startedAt = now,
        finishedAt = now,
        createdAt = now,
    )
}

private fun chunk(
    scope: String,
    document: DocumentSnapshot,
    taskId: TaskId? = null,
    runId: RunId? = null,
    chunkHash: String = "chunk-hash-$scope",
): DocumentChunk {
    val id = DocumentChunkId(stableUuid("$scope-chunk"))
    return DocumentChunk(
        id = id,
        projectId = document.projectId,
        iterationId = document.iterationId,
        documentId = document.id,
        taskId = taskId,
        runId = runId,
        artifactType = document.artifactType,
        sourcePath = document.sourcePath,
        chunkIndex = 0,
        content = "Chunk content for $scope",
        chunkHash = ContentHash(chunkHash),
        tokenEstimate = 6,
        sourceReference = sourceReference(id.value, document.sourcePath),
        createdAt = now,
    )
}

private fun documentCommand(
    scope: String,
    projectId: ProjectId,
    iterationId: IterationId,
    sourcePath: String,
    rawSourcePath: String = sourcePath,
    contentHash: String,
    content: String = "Document content for $scope",
): SaveDocumentSnapshotCommand {
    val id = DocumentId(stableUuid("$scope-document-command"))
    return SaveDocumentSnapshotCommand(
        id = id,
        projectId = projectId,
        iterationId = iterationId,
        sourceDocumentId = SourceDocumentId("source-document-$scope"),
        sourcePath = sourcePath,
        snapshotVersion = 1,
        artifactType = ArtifactType.DOCUMENT_SNAPSHOT,
        title = "Document $scope",
        content = content,
        contentHash = ContentHash(contentHash),
        sourceReference = sourceReference(id.value, rawSourcePath),
        capturedAt = now,
        createdAt = now,
    )
}

private fun embeddingSet(
    scope: String,
    projectId: ProjectId,
    model: String,
    version: String,
    dimension: Int = 2,
): EmbeddingSet =
    EmbeddingSet(
        id = EmbeddingSetId(stableUuid("$scope-embedding-set")),
        projectId = projectId,
        embeddingModel = model,
        embeddingDimension = dimension,
        embeddingVersion = version,
        distanceMetric = DistanceMetric.COSINE,
        storageType = EmbeddingStorageType.VECTOR_INDEX,
        createdAt = now,
    )

private fun sourceReference(canonicalId: String, path: String): SourceReference =
    SourceReference(
        canonicalServerId = CanonicalServerId(canonicalId),
        uri = "file:///repo/$path",
        path = path,
    )

private fun stableUuid(seed: String): String =
    UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()

private fun List<Float>.toPgVectorLiteral(): String =
    joinToString(separator = ",", prefix = "[", postfix = "]") { it.toString() }

private fun seedV1EmbeddingFixture(jdbcUrl: String) {
    val projectId = UUID.fromString(stableUuid("v1-fixture-project"))
    val documentId = UUID.fromString(stableUuid("v1-fixture-document"))
    val chunkId = UUID.fromString(stableUuid("v1-fixture-chunk"))
    val legacy384SetId = UUID.fromString(stableUuid("v1-fixture-384-set"))
    val legacy2SetId = UUID.fromString(stableUuid("v1-fixture-2-set"))
    val legacy384EmbeddingId = UUID.fromString(stableUuid("v1-fixture-384-embedding"))
    val legacy2EmbeddingId = UUID.fromString(stableUuid("v1-fixture-2-embedding"))
    val vector384 = List(384) { index -> if (index == 0) 1f else 0f }.toPgVectorLiteral()

    DriverManager.getConnection(jdbcUrl, PostgresStorageIntegrationTest.postgres.username, PostgresStorageIntegrationTest.postgres.password).use { connection ->
        connection.prepareStatement(
            "INSERT INTO projects (project_id, source_project_id, name, root_path) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, projectId)
            statement.setString(2, "v1-fixture-project")
            statement.setString(3, "V1 fixture project")
            statement.setString(4, "/v1-fixture")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO documents (
                document_id, source_document_id, project_id, artifact_type, source_path,
                content_hash, snapshot_version, content
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, documentId)
            statement.setString(2, "v1-fixture-document")
            statement.setObject(3, projectId)
            statement.setString(4, "DOCUMENT_SNAPSHOT")
            statement.setString(5, "docs/v1-fixture.md")
            statement.setString(6, "v1-fixture-document-hash")
            statement.setInt(7, 1)
            statement.setString(8, "V1 fixture content")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO document_chunks (
                chunk_id, source_chunk_id, document_id, project_id, artifact_type, source_path,
                chunk_index, chunk_hash, content
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, chunkId)
            statement.setString(2, "v1-fixture-chunk")
            statement.setObject(3, documentId)
            statement.setObject(4, projectId)
            statement.setString(5, "DOCUMENT_SNAPSHOT")
            statement.setString(6, "docs/v1-fixture.md")
            statement.setInt(7, 0)
            statement.setString(8, "v1-fixture-chunk-hash")
            statement.setString(9, "V1 fixture chunk")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO embedding_sets (
                embedding_set_id, project_id, embedding_model, embedding_dimension,
                embedding_version, distance_metric, storage_type
            ) VALUES (?, ?, ?, ?, ?, 'cosine', 'vector')
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, legacy384SetId)
            statement.setObject(2, projectId)
            statement.setString(3, "legacy-384")
            statement.setInt(4, 384)
            statement.setString(5, "v1")
            statement.executeUpdate()
            statement.setObject(1, legacy2SetId)
            statement.setObject(2, projectId)
            statement.setString(3, "legacy-2")
            statement.setInt(4, 2)
            statement.setString(5, "v1")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO chunk_embeddings (
                chunk_embedding_id, chunk_id, embedding_set_id, embedding
            ) VALUES (?, ?, ?, CAST(? AS vector))
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, legacy384EmbeddingId)
            statement.setObject(2, chunkId)
            statement.setObject(3, legacy384SetId)
            statement.setString(4, vector384)
            statement.executeUpdate()
            statement.setObject(1, legacy2EmbeddingId)
            statement.setObject(2, chunkId)
            statement.setObject(3, legacy2SetId)
            statement.setString(4, "[0.0,1.0]")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO chunk_embedding_vectors_2 (chunk_embedding_id, embedding) VALUES (?, CAST(? AS vector(2)))",
        ).use { statement ->
            statement.setObject(1, legacy2EmbeddingId)
            statement.setString(2, "[0.0,1.0]")
            statement.executeUpdate()
        }
    }
}

private val now: Instant = Instant.parse("2026-06-29T00:00:00Z")

private fun graphNode(
    seed: String,
    projectId: ProjectId,
    iterationId: IterationId,
    naturalKey: String,
    kind: ArtifactNodeKind,
): ArtifactNode = ArtifactNode(
    id = ArtifactNodeId(stableUuid(seed)),
    projectId = projectId,
    iterationId = iterationId,
    kind = kind,
    naturalKey = naturalKey,
    label = naturalKey,
)

private fun graphEdge(
    seed: String,
    projectId: ProjectId,
    fromNodeId: ArtifactNodeId,
    toNodeId: ArtifactNodeId,
    type: ArtifactEdgeType,
): ArtifactEdge = ArtifactEdge(
    id = ArtifactEdgeId(stableUuid(seed)),
    projectId = projectId,
    fromNodeId = fromNodeId,
    toNodeId = toNodeId,
    type = type,
)
