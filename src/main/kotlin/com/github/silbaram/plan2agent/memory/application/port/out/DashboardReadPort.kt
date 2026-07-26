package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.application.usecase.IterationSummaryPageQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.ProjectSummaryPageQuery
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactDetail
import com.github.silbaram.plan2agent.memory.domain.readmodel.ArtifactIdentity
import com.github.silbaram.plan2agent.memory.domain.readmodel.IterationSummary
import com.github.silbaram.plan2agent.memory.domain.readmodel.ProjectSummary

/**
 * Read-only persistence boundary for dashboard hierarchy pages and artifact content.
 *
 * Page cursors here are opaque adapter continuation keys. The application service wraps them in
 * a request-bound public cursor before they leave the application boundary.
 */
interface DashboardReadPort {
    fun findProjectSummaries(query: ProjectSummaryPageQuery): PagedResult<ProjectSummary>

    fun findIterationSummaries(query: IterationSummaryPageQuery): PagedResult<IterationSummary>

    fun findArtifactDetail(identity: ArtifactIdentity): ArtifactDetail?
}
