package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.application.usecase.VectorSearchQuery
import com.github.silbaram.plan2agent.memory.domain.VectorSearchMatch

interface VectorSearchPort {
    fun search(query: VectorSearchQuery): PagedResult<VectorSearchMatch>
}
