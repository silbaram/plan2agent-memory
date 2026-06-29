package com.github.silbaram.plan2agent.memory.adapter.out.postgres

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.silbaram.plan2agent.memory.domain.CanonicalServerId
import com.github.silbaram.plan2agent.memory.domain.SourceReference

internal class PostgresJsonSupport(
    private val objectMapper: ObjectMapper,
) {
    private val stringMapType = object : TypeReference<Map<String, String>>() {}
    private val stringListType = object : TypeReference<List<String>>() {}
    private val dependencyEdgeListType = object : TypeReference<List<DependencyEdgeJson>>() {}
    private val artifactRefListType = object : TypeReference<List<ArtifactRefJson>>() {}

    fun metadataToJson(metadata: Map<String, String>): String =
        objectMapper.writeValueAsString(metadata)

    fun metadataFromJson(json: String?): Map<String, String> =
        if (json.isNullOrBlank()) {
            emptyMap()
        } else {
            objectMapper.readValue(json, stringMapType)
        }

    fun stringsToJson(values: Collection<String>): String =
        objectMapper.writeValueAsString(values)

    fun stringsFromJson(json: String?): List<String> =
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            objectMapper.readValue(json, stringListType)
        }

    fun dependencyEdgesToJson(values: Collection<DependencyEdgeJson>): String =
        objectMapper.writeValueAsString(values)

    fun dependencyEdgesFromJson(json: String?): List<DependencyEdgeJson> =
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            objectMapper.readValue(json, dependencyEdgeListType)
        }

    fun artifactRefsToJson(values: Collection<ArtifactRefJson>): String =
        objectMapper.writeValueAsString(values)

    fun artifactRefsFromJson(json: String?): List<ArtifactRefJson> =
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            objectMapper.readValue(json, artifactRefListType)
        }

    fun withSourceReference(metadata: Map<String, String>, sourceReference: SourceReference?): Map<String, String> {
        if (sourceReference == null) {
            return metadata
        }
        return metadata + mapOf(
            SOURCE_REF_CANONICAL_SERVER_ID to sourceReference.canonicalServerId.value,
            SOURCE_REF_URI to sourceReference.uri,
        ) + listOfNotNull(
            sourceReference.path?.let { SOURCE_REF_PATH to it },
            sourceReference.startLine?.let { SOURCE_REF_START_LINE to it.toString() },
            sourceReference.endLine?.let { SOURCE_REF_END_LINE to it.toString() },
            sourceReference.fragment?.let { SOURCE_REF_FRAGMENT to it },
        )
    }

    fun sourceReferenceFrom(metadata: Map<String, String>): SourceReference? {
        val canonicalServerId = metadata[SOURCE_REF_CANONICAL_SERVER_ID] ?: return null
        val uri = metadata[SOURCE_REF_URI] ?: return null
        return SourceReference(
            canonicalServerId = CanonicalServerId(canonicalServerId),
            uri = uri,
            path = metadata[SOURCE_REF_PATH],
            startLine = metadata[SOURCE_REF_START_LINE]?.toIntOrNull(),
            endLine = metadata[SOURCE_REF_END_LINE]?.toIntOrNull(),
            fragment = metadata[SOURCE_REF_FRAGMENT],
        )
    }

    fun withoutReservedMetadata(metadata: Map<String, String>): Map<String, String> =
        metadata.filterKeys { it !in RESERVED_METADATA_KEYS }

    companion object {
        const val PROJECT_CANONICAL_SERVER_ID = "p2a.canonicalServerId"
        const val DOCUMENT_TITLE = "p2a.document.title"
        const val DOCUMENT_CAPTURED_AT = "p2a.document.capturedAt"
        const val TASK_GRAPH_TASK_IDS = "p2a.taskGraph.taskIds"
        const val TASK_GRAPH_DEPENDENCY_EDGES = "p2a.taskGraph.dependencyEdges"

        private const val SOURCE_REF_CANONICAL_SERVER_ID = "p2a.sourceReference.canonicalServerId"
        private const val SOURCE_REF_URI = "p2a.sourceReference.uri"
        private const val SOURCE_REF_PATH = "p2a.sourceReference.path"
        private const val SOURCE_REF_START_LINE = "p2a.sourceReference.startLine"
        private const val SOURCE_REF_END_LINE = "p2a.sourceReference.endLine"
        private const val SOURCE_REF_FRAGMENT = "p2a.sourceReference.fragment"

        private val RESERVED_METADATA_KEYS = setOf(
            PROJECT_CANONICAL_SERVER_ID,
            DOCUMENT_TITLE,
            DOCUMENT_CAPTURED_AT,
            TASK_GRAPH_TASK_IDS,
            TASK_GRAPH_DEPENDENCY_EDGES,
            SOURCE_REF_CANONICAL_SERVER_ID,
            SOURCE_REF_URI,
            SOURCE_REF_PATH,
            SOURCE_REF_START_LINE,
            SOURCE_REF_END_LINE,
            SOURCE_REF_FRAGMENT,
        )
    }
}

internal data class DependencyEdgeJson(
    val fromTaskId: String,
    val toTaskId: String,
)

internal data class ArtifactRefJson(
    val artifactType: String,
    val artifactId: String,
    val sourcePath: String? = null,
)
