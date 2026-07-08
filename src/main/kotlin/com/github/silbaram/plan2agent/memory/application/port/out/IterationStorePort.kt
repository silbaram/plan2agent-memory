package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.Iteration
import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.ProjectId

interface IterationStorePort {
    fun save(iteration: Iteration): Iteration
    fun findById(id: IterationId): Iteration?
    fun findByProjectId(projectId: ProjectId): List<Iteration>
}
