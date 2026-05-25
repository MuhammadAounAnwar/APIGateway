package com.ono.apigateway.resillience

import io.micrometer.tracing.Tracer
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/fallback")
class GatewayFallbackController(
    private val tracer: Tracer
) {

    private val knownServices = setOf(
        "AUTH-SERVICE", "USER-SERVICE", "ORDER-SERVICE",
        "RESTAURANT-SERVICE", "NOTIFICATION-SERVICE",
        "EMAIL-SERVICE", "CHAT-SERVICE", "SPOZON-BACKEND",
        "PICKLPLAY-BACKEND"
    )

    private fun buildFallbackResponse(
        request: ServerHttpRequest,
        serviceName: String,
        message: String
    ): ResponseEntity<Map<String, Any>> {

        val traceId = tracer.currentSpan()?.context()?.traceId() ?: "UNKNOWN"

        val body = mapOf(
            "timestamp" to Instant.now().toString(),
            "status" to HttpStatus.SERVICE_UNAVAILABLE.value(),
            "error" to HttpStatus.SERVICE_UNAVAILABLE.reasonPhrase,
            "message" to message,
            "service" to serviceName,
            "path" to request.uri.path,
            "traceId" to traceId
        )

        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(body)
    }

    @RequestMapping("/{service}")
    fun serviceFallback(
        request: ServerHttpRequest,
        @PathVariable service: String
    ): ResponseEntity<Map<String, Any>> {

        val normalizedService = service.uppercase()
        if (normalizedService !in knownServices) {
            return ResponseEntity.notFound().build()
        }

        return buildFallbackResponse(
            request,
            normalizedService,
            "$normalizedService is temporarily unavailable"
        )
    }
}