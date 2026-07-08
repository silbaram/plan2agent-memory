package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.Project
import com.github.silbaram.plan2agent.memory.domain.ProjectId

interface ProjectStorePort {
    fun save(project: Project): Project
    fun findById(id: ProjectId): Project?
}
