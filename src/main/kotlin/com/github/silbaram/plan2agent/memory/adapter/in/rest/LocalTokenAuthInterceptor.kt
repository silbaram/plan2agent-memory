package com.github.silbaram.plan2agent.memory.adapter.`in`.rest

import com.github.silbaram.plan2agent.memory.config.LocalSecurityProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Component
class LocalTokenAuthInterceptor(
    private val properties: LocalSecurityProperties,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val expectedToken = properties.token?.takeIf { it.isNotBlank() } ?: return true
        val actualToken = request.getHeader(properties.headerName)
        if (actualToken != expectedToken) {
            throw RestAuthException("Missing or invalid local API token")
        }
        return true
    }
}

@Component
class LocalTokenWebMvcConfigurer(
    private val localTokenAuthInterceptor: LocalTokenAuthInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(localTokenAuthInterceptor)
            .addPathPatterns("/api/**")
    }
}
