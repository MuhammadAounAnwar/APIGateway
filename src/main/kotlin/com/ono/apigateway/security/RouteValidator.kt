package com.ono.apigateway.security

import org.springframework.stereotype.Component

@Component
class RouteValidator {

    private val openApiEndpoints = listOf(
        "/auth/login",
        "/auth/register",
        "/auth/forgot-password",
        "/auth/reset-password",
        "/auth/refresh",
        "/ws/"              // WebSocket connections — JWT validated in handler
    )

    fun isSecured(uri: String): Boolean {
        return openApiEndpoints.none { uri.contains(it) }
    }

}