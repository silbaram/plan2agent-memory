package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.Task
import com.github.silbaram.plan2agent.memory.domain.TaskGraphId
import com.github.silbaram.plan2agent.memory.domain.TaskId

interface TaskStorePort {
    fun saveAll(tasks: List<Task>): List<Task>
    fun findById(id: TaskId): Task?
    fun findByGraphId(graphId: TaskGraphId): List<Task>
}
