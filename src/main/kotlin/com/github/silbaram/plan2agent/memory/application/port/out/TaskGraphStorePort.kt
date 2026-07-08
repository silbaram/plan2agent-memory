package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.IterationId
import com.github.silbaram.plan2agent.memory.domain.TaskGraph
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId

interface TaskGraphStorePort {
    fun save(taskGraph: TaskGraph): TaskGraph
    fun findById(id: TaskGraphId): TaskGraph?
    fun findByIterationId(iterationId: IterationId): List<TaskGraph>
}
