package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.RunId
import com.github.silbaram.plan2agent.memory.domain.RunRecord
import com.github.silbaram.plan2agent.memory.domain.TaskId

interface RunRecordStorePort {
    fun save(runRecord: RunRecord): RunRecord
    fun findById(id: RunId): RunRecord?
    fun findByTaskId(taskId: TaskId): List<RunRecord>
}
