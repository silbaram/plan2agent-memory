package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.application.usecase.ArtifactGraphSnapshotResult
import com.github.silbaram.plan2agent.memory.application.usecase.GraphNodeSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.GraphTraceQuery
import com.github.silbaram.plan2agent.memory.domain.ArtifactEdge
import com.github.silbaram.plan2agent.memory.domain.ArtifactNode
import com.github.silbaram.plan2agent.memory.domain.ArtifactTrace
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.ProjectId

interface ArtifactGraphStorePort {
    fun replaceSnapshot(projectId: ProjectId, iterationId: IterationId?, nodes: List<ArtifactNode>, edges: List<ArtifactEdge>): ArtifactGraphSnapshotResult
    fun findNodes(query: GraphNodeSearchQuery): List<ArtifactNode>
    fun trace(query: GraphTraceQuery): ArtifactTrace
}
