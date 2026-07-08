package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.application.usecase.KeywordSearchQuery
import com.github.silbaram.plan2agent.memory.application.usecase.PagedResult
import com.github.silbaram.plan2agent.memory.domain.KeywordSearchMatch

interface KeywordSearchPort {
    fun search(query: KeywordSearchQuery): PagedResult<KeywordSearchMatch>
}
