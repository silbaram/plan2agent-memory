package com.github.silbaram.plan2agent.memory.domain

data class SourceReference(
    val canonicalServerId: CanonicalServerId,
    val uri: String,
    val path: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val fragment: String? = null,
) {
    init {
        require(uri.isNotBlank()) { "SourceReference uri must not be blank" }
        require(startLine == null || startLine > 0) { "SourceReference startLine must be positive" }
        require(endLine == null || endLine > 0) { "SourceReference endLine must be positive" }
        require(startLine == null || endLine == null || startLine <= endLine) {
            "SourceReference startLine must not exceed endLine"
        }
    }
}
