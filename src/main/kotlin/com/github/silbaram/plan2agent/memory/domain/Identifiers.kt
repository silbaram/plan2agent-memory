package com.github.silbaram.plan2agent.memory.domain

@JvmInline
value class ProjectId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProjectId must not be blank" }
    }
}

@JvmInline
value class IterationId(val value: String) {
    init {
        require(value.isNotBlank()) { "IterationId must not be blank" }
    }
}

@JvmInline
value class DocumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "DocumentId must not be blank" }
    }
}

@JvmInline
value class TaskGraphId(val value: String) {
    init {
        require(value.isNotBlank()) { "TaskGraphId must not be blank" }
    }
}

@JvmInline
value class TaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "TaskId must not be blank" }
    }
}

@JvmInline
value class RunId(val value: String) {
    init {
        require(value.isNotBlank()) { "RunId must not be blank" }
    }
}

@JvmInline
value class DocumentChunkId(val value: String) {
    init {
        require(value.isNotBlank()) { "DocumentChunkId must not be blank" }
    }
}

@JvmInline
value class EmbeddingSetId(val value: String) {
    init {
        require(value.isNotBlank()) { "EmbeddingSetId must not be blank" }
    }
}

@JvmInline
value class ChunkEmbeddingId(val value: String) {
    init {
        require(value.isNotBlank()) { "ChunkEmbeddingId must not be blank" }
    }
}

@JvmInline
value class CanonicalServerId(val value: String) {
    init {
        require(value.isNotBlank()) { "CanonicalServerId must not be blank" }
    }
}

@JvmInline
value class ContentHash(val value: String) {
    init {
        require(value.isNotBlank()) { "ContentHash must not be blank" }
    }
}

@JvmInline
value class SourceProjectId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceProjectId must not be blank" }
    }
}

@JvmInline
value class SourceIterationId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceIterationId must not be blank" }
    }
}

@JvmInline
value class SourceDocumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceDocumentId must not be blank" }
    }
}

@JvmInline
value class SourceTaskGraphId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceTaskGraphId must not be blank" }
    }
}

@JvmInline
value class SourceTaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceTaskId must not be blank" }
    }
}

@JvmInline
value class SourceRunId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceRunId must not be blank" }
    }
}

@JvmInline
value class Embedding(val values: List<Float>) {
    init {
        require(values.isNotEmpty()) { "Embedding must contain at least one dimension" }
    }
}
