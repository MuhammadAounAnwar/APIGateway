package com.ono.apigateway.security

import org.springframework.stereotype.Component

@Component
class RouteValidator {

    private val openApiEndpoints = listOf("/auth/login", "/auth/register")

    fun isSecured(uri: String): Boolean {
        return openApiEndpoints.none { uri.contains(it) }
    }

}