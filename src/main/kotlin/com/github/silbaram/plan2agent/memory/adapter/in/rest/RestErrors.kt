package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderException
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.Instant

data class RestErrorResponse(
    val error: String,
    val message: String,
    val status: Int,
    val timestamp: Instant = Instant.now(),
    val details: Map<String, String> = emptyMap(),
)

class RestAuthException(message: String) : RuntimeException(message)
class RestNotFoundException(message: String) : RuntimeException(message)

@RestControllerAdvice
class RestExceptionHandler {
    @ExceptionHandler(ProviderNotConfiguredException::class)
    fun embeddingProviderNotConfigured(exception: ProviderNotConfiguredException): ResponseEntity<RestErrorResponse> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "embedding_provider_not_configured", exception)

    @ExceptionHandler(EmbeddingProviderException::class)
    fun embeddingProviderUnavailable(exception: EmbeddingProviderException): ResponseEntity<RestErrorResponse> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "embedding_provider_unavailable", exception)

    @ExceptionHandler(
        IllegalArgumentException::class,
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun validation(exception: Exception): ResponseEntity<RestErrorResponse> {
        if (exception is IllegalArgumentException && exception.message?.contains("not found", ignoreCase = true) == true) {
            return error(HttpStatus.NOT_FOUND, "not_found", exception)
        }
        return error(HttpStatus.BAD_REQUEST, "validation_error", exception)
    }

    @ExceptionHandler(RestAuthException::class)
    fun auth(exception: RestAuthException): ResponseEntity<RestErrorResponse> =
        error(HttpStatus.UNAUTHORIZED, "auth_error", exception)

    @ExceptionHandler(RestNotFoundException::class, NoSuchElementException::class)
    fun notFound(exception: Exception): ResponseEntity<RestErrorResponse> =
        error(HttpStatus.NOT_FOUND, "not_found", exception)

    @ExceptionHandler(IllegalStateException::class)
    fun conflict(exception: IllegalStateException): ResponseEntity<RestErrorResponse> =
        error(HttpStatus.CONFLICT, "conflict", exception)

    private fun error(
        status: HttpStatus,
        code: String,
        exception: Exception,
    ): ResponseEntity<RestErrorResponse> =
        ResponseEntity.status(status).body(
            RestErrorResponse(
                error = code,
                message = safeMessage(code, status, exception),
                status = status.value(),
            ),
        )

    /**
     * API errors can be caused by provider exceptions and malformed input. Do not reflect exception
     * messages because they can contain raw provider bodies, credentials, local paths or content.
     */
    private fun safeMessage(code: String, status: HttpStatus, exception: Exception): String =
        when (code) {
            "embedding_provider_not_configured" -> "Embedding provider is not configured"
            "embedding_provider_unavailable" -> "Embedding provider is unavailable"
            "auth_error" -> "Authentication failed"
            "not_found" -> "Requested resource was not found"
            else -> exception.message
                ?.takeIf { message -> message.isSafeForApi() }
                ?: status.reasonPhrase
        }

    private fun String.isSafeForApi(): Boolean =
        isNotBlank() && SENSITIVE_ERROR_TEXT.containsMatchIn(this).not()

    private companion object {
        /** Blocks common provider response, credential, stack-trace and filesystem disclosures. */
        val SENSITIVE_ERROR_TEXT = Regex(
            pattern = "(?i)(?:[\\r\\n]|credential|password|authorization|bearer\\s+|token[=:]|file:|/private/|/users/|/home/|\\bat\\s+[^\\s]+\\([^)]*:\\d+\\))",
        )
    }
}
