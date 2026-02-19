package com.ono.apigateway.util

import io.micrometer.tracing.Tracer
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono


// ---------------- Extension & Helper Functions ----------------

data class AuthContext(
    val jwt: Jwt,
    val roles: String
)

fun ServerWebExchange.requireAuthContext(): Mono<AuthContext> {
    return this.getPrincipal<Authentication>()
        .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED)))
        .cast(Authentication::class.java)
        .flatMap { auth ->
            val jwt = auth.principal as? Jwt
                ?: return@flatMap Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED))

            val roles = auth.authorities
                .joinToString(",") { it.authority }

            Mono.just(AuthContext(jwt, roles))
        }
}

fun Tracer.tag(key: String, value: String?) {
    this.currentSpan()?.tag(key, value ?: "null")
}

fun Tracer.event(name: String) {
    this.currentSpan()?.event(name)
}

fun Tracer.tagRateLimit(type: String, key: String, allowed: Boolean) {
    this.tag("rate.limit.type", type)
    this.tag("rate.limit.key", key)
    this.tag("rate.limit.allowed", allowed.toString())
}

fun unauthorized(message: String = "Unauthorized"): Mono<Nothing> =
    Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED, message))

fun tooManyRequests(): Mono<Nothing> =
    Mono.error(ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"))