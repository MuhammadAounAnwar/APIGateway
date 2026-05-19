package com.ono.apigateway.security

import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher

@Component
class RouteValidator {

    private val matcher = AntPathMatcher()

    private val openApiPatterns = listOf(
        "/api/auth/**",
        "/oauth2/**",
        "/ws/**",
        "/actuator/health",
        "/actuator/health/**"
    )

    fun isSecured(uri: String): Boolean {
        return openApiPatterns.none { matcher.match(it, uri) }
    }

}
