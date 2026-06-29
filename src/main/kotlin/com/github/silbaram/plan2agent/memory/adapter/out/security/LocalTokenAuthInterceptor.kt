package com.github.silbaram.plan2agent.memory.adapter.out.security

import com.github.silbaram.plan2agent.memory.adapter.`in`.rest.RestAuthException
import com.github.silbaram.plan2agent.memory.config.LocalSecurityProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

@Component
class LocalTokenAuthInterceptor(
    private val properties: LocalSecurityProperties,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val expectedToken = configuredToken() ?: return true
        if (isExcluded(request)) return true

        val actualToken = request.getHeader(properties.headerName)
        if (actualToken == null || !actualToken.constantTimeEquals(expectedToken)) {
            throw RestAuthException("Missing or invalid local API token")
        }
        return true
    }

    private fun configuredToken(): String? =
        properties.token?.takeIf { it.isNotBlank() }

    private fun isExcluded(request: HttpServletRequest): Boolean {
        val contextPath = request.contextPath.orEmpty()
        val requestPath = request.requestURI.removePrefix(contextPath)
        return requestPath in properties.excludedPaths
    }
}

@Component
class LocalTokenWebMvcConfigurer(
    private val properties: LocalSecurityProperties,
    private val localTokenAuthInterceptor: LocalTokenAuthInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(localTokenAuthInterceptor)
            .addPathPatterns(properties.protectedPaths)
            .excludePathPatterns(properties.excludedPaths)
    }
}

private fun String.constantTimeEquals(expected: String): Boolean {
    val actualBytes = toByteArray(StandardCharsets.UTF_8)
    val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
    return MessageDigest.isEqual(actualBytes, expectedBytes)
}
