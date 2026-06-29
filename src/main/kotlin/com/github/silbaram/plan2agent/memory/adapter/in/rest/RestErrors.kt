package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

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
                message = exception.message ?: status.reasonPhrase,
                status = status.value(),
            ),
        )
}
