package com.ono.apigateway.security

import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher

@Component
class RouteValidator {

    private val matcher = AntPathMatcher()

    // Routes open to any HTTP method — no JWT required
    private val openAnyMethod = listOf(
        "/api/auth/**",
        "/api/v1/guest/**",
        "/api/v1/public/**",
        "/oauth2/**",
        "/ws/**",
        "/actuator/health",
        "/actuator/health/**",
    )

    // GET-only open routes — readable by guests, mutations remain secured
    private val openGetPatterns = listOf(
        "/api/v1/venues",
        "/api/v1/venues/*",
        "/api/v1/venues/*/reviews",
        "/api/v1/venues/*/courts",
        "/api/v1/venues/*/courts/*/slots",
        "/api/v1/courts",
        "/api/v1/courts/**",
    )

    fun isSecured(method: HttpMethod, uri: String): Boolean {
        if (openAnyMethod.any { matcher.match(it, uri) }) return false
        if (method == HttpMethod.GET && openGetPatterns.any { matcher.match(it, uri) }) return false
        return true
    }

    // Kept for backwards-compatibility with callers that don't have the method
    fun isSecured(uri: String): Boolean = isSecured(HttpMethod.GET, uri)
}
