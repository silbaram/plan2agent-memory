package com.github.silbaram.plan2agent.memory.adapter.out.security

import com.github.silbaram.plan2agent.memory.adapter.`in`.rest.RestAuthException
import com.github.silbaram.plan2agent.memory.adapter.`in`.rest.RestExceptionHandler
import com.github.silbaram.plan2agent.memory.config.LocalSecurityProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class LocalTokenAuthInterceptorTest {
    @Test
    fun `configured token is required for protected API requests`() {
        val interceptor = interceptor(token = "local-secret")
        val request = request("/api/projects")
        request.addHeader("X-P2A-Local-Token", "local-secret")

        val result = interceptor.preHandle(request, MockHttpServletResponse(), Any())

        assertThat(result).isTrue()
    }

    @Test
    fun `missing or invalid token throws auth exception`() {
        val interceptor = interceptor(token = "local-secret")

        assertThatThrownBy {
            interceptor.preHandle(request("/api/projects"), MockHttpServletResponse(), Any())
        }
            .isInstanceOf(RestAuthException::class.java)
            .hasMessageContaining("Missing or invalid local API token")

        val invalidRequest = request("/api/projects")
        invalidRequest.addHeader("X-P2A-Local-Token", "wrong")

        assertThatThrownBy {
            interceptor.preHandle(invalidRequest, MockHttpServletResponse(), Any())
        }
            .isInstanceOf(RestAuthException::class.java)
            .hasMessageContaining("Missing or invalid local API token")
    }

    @Test
    fun `blank token keeps local development endpoints open`() {
        val interceptor = interceptor(token = "")

        val result = interceptor.preHandle(request("/api/projects"), MockHttpServletResponse(), Any())

        assertThat(result).isTrue()
    }

    @Test
    fun `health endpoints bypass local token checks`() {
        val interceptor = interceptor(token = "local-secret")

        assertThat(interceptor.preHandle(request("/api/health"), MockHttpServletResponse(), Any())).isTrue()
        assertThat(interceptor.preHandle(request("/actuator/health"), MockHttpServletResponse(), Any())).isTrue()
    }

    @Test
    fun `custom header name is honored`() {
        val interceptor = LocalTokenAuthInterceptor(
            LocalSecurityProperties(
                token = "local-secret",
                headerName = "X-Custom-Token",
            ),
        )
        val request = request("/api/search/keyword")
        request.addHeader("X-Custom-Token", "local-secret")

        assertThat(interceptor.preHandle(request, MockHttpServletResponse(), Any())).isTrue()
    }

    @Test
    fun `auth exception maps to distinct auth error response`() {
        val response = RestExceptionHandler().auth(RestAuthException("Missing or invalid local API token"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body?.error).isEqualTo("auth_error")
        assertThat(response.body?.status).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun `security configuration rejects blank header and protected paths`() {
        assertThatThrownBy { LocalSecurityProperties(headerName = " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("header-name must not be blank")

        assertThatThrownBy { LocalSecurityProperties(protectedPaths = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("protected-paths must not be empty")
    }

    private fun interceptor(token: String?): LocalTokenAuthInterceptor =
        LocalTokenAuthInterceptor(LocalSecurityProperties(token = token))

    private fun request(path: String): MockHttpServletRequest =
        MockHttpServletRequest("GET", path)
}
