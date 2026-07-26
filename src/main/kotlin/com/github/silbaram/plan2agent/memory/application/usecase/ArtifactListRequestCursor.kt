package com.github.silbaram.plan2agent.memory.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Produces the stable identity of an artifact-list page request.
 *
 * The continuation key itself stays adapter-owned. This fingerprint intentionally omits the
 * page limit so callers can choose a different page size while continuing the same result set.
 */
internal object ArtifactListRequestCursor {
    private const val endpoint = "GET /api/artifacts"
    private val objectMapper = ObjectMapper()

    fun fingerprint(query: FindArtifactsQuery): String {
        val canonicalRequest = linkedMapOf<String, Any?>(
            "endpoint" to endpoint,
            "projectId" to query.projectId?.value,
            "iterationId" to query.iterationId?.value,
            "artifactTypes" to query.normalizedArtifactTypes.map { it.name },
            "sourceProjectId" to query.sourceProjectId?.value,
            "sourceIterationId" to query.sourceIterationId?.value,
            "sourceDocumentId" to query.sourceDocumentId?.value,
            "sourceTaskGraphId" to query.sourceTaskGraphId?.value,
            "sourceTaskId" to query.sourceTaskId?.value,
            "sourceRunId" to query.sourceRunId?.value,
            "sourcePath" to query.sourcePath,
            "taskId" to query.taskId?.value,
            "runId" to query.runId?.value,
            "contentHash" to query.contentHash?.value,
            "sourceReferenceCanonicalServerId" to query.sourceReference?.canonicalServerId?.value,
            "sourceReferenceUri" to query.sourceReference?.uri,
        )
        val canonicalJson = objectMapper.writeValueAsString(canonicalRequest)
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(StandardCharsets.UTF_8)),
        )
    }
}
