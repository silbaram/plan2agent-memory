package com.github.silbaram.plan2agent.memory.application.usecase

import com.github.silbaram.plan2agent.memory.application.port.`in`.EmbeddingJobManagementUseCase
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingJobStorePort
import com.github.silbaram.plan2agent.memory.application.port.out.FindEmbeddingJobsQuery
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJob
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobId
import com.github.silbaram.plan2agent.memory.domain.EmbeddingJobStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

const val DEFAULT_EMBEDDING_JOB_PAGE_LIMIT = 50
const val MAX_EMBEDDING_JOB_PAGE_LIMIT = 200

@Service
class EmbeddingJobManagementUseCaseService(
    private val embeddingJobStore: EmbeddingJobStorePort,
) : EmbeddingJobManagementUseCase {
    @Transactional(readOnly = true)
    override fun findEmbeddingJobs(query: FindEmbeddingJobsQuery): PagedResult<EmbeddingJob> {
        require(query.limit in 1..MAX_EMBEDDING_JOB_PAGE_LIMIT) {
            "Embedding job limit must be between 1 and $MAX_EMBEDDING_JOB_PAGE_LIMIT"
        }
        return embeddingJobStore.findPage(query)
    }

    @Transactional(readOnly = true)
    override fun findEmbeddingJob(jobId: EmbeddingJobId): EmbeddingJob =
        embeddingJobStore.findById(jobId)
            ?: throw NoSuchElementException("Embedding job ${jobId.value} was not found")

    @Transactional
    override fun retryEmbeddingJob(jobId: EmbeddingJobId): EmbeddingJob {
        val job = embeddingJobStore.retryFailed(jobId)
            ?: throw NoSuchElementException("Embedding job ${jobId.value} was not found")
        check(job.status != EmbeddingJobStatus.SUCCEEDED) {
            "Succeeded embedding jobs cannot be retried"
        }
        return job
    }
}
