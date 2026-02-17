package com.ono.apigateway

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.server.reactive.ServerHttpRequest
import java.time.Instant

@RestController
@RequestMapping("/fallback")
class GatewayFallbackController {

    companion object {
        private const val CORRELATION_ID_HEADER = "X-Correlation-Id"
    }

    private fun buildFallbackResponse(
        request: ServerHttpRequest,
        serviceName: String,
        message: String
    ): ResponseEntity<Map<String, Any>> {

        val correlationId =
            request.headers.getFirst(CORRELATION_ID_HEADER) ?: "UNKNOWN"

        val body = mapOf(
            "timestamp" to Instant.now().toString(),
            "status" to HttpStatus.SERVICE_UNAVAILABLE.value(),
            "error" to HttpStatus.SERVICE_UNAVAILABLE.reasonPhrase,
            "message" to message,
            "service" to serviceName,
            "path" to request.uri.path,
            "correlationId" to correlationId
        )

        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(body)
    }

    @GetMapping("/{service}")
    fun serviceFallback(
        request: ServerHttpRequest,
        @org.springframework.web.bind.annotation.PathVariable service: String
    ): ResponseEntity<Map<String, Any>> {

        val normalizedService = service.uppercase()

        return buildFallbackResponse(
            request,
            normalizedService,
            "$normalizedService is temporarily unavailable"
        )
    }
}
