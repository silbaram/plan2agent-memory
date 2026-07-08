package com.github.silbaram.plan2agent.memory.application.port.out

import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.SourceReference

interface SourceReferenceResolutionPort {
    fun resolve(canonicalServerId: CanonicalServerId, rawReference: String): SourceReference?
}
