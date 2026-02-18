package com.ono.apigateway.filter

import io.micrometer.tracing.Tracer
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeoutException

@Component
@Order(-1) // must be outermost
class ErrorHandlingFilter(
    private val tracer: Tracer
) : GlobalFilter {

    private val log = LoggerFactory.getLogger(ErrorHandlingFilter::class.java)

    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain
    ): Mono<Void> {

        return chain.filter(exchange)
            .onErrorResume { ex ->
                handleException(exchange, ex)
            }
    }

    private fun handleException(
        exchange: ServerWebExchange,
        ex: Throwable
    ): Mono<Void> {

        val span = tracer.currentSpan()

        // Record tracing error
        span?.error(ex)
        span?.tag("error", "true")
        span?.tag("error.type", ex.javaClass.simpleName)

        val status = resolveHttpStatus(ex)

        log.error(
            "Gateway error: path={}, status={}, traceId={}",
            exchange.request.path,
            status.value(),
            span?.context()?.traceId(),
            ex
        )

        if (exchange.response.isCommitted) {
            return Mono.error(ex)
        }

        exchange.response.statusCode = status
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON

        val traceId = span?.context()?.traceId()

        val body = buildErrorResponse(
            exchange = exchange,
            status = status,
            message = safeMessage(ex),
            traceId = traceId
        )

        val buffer = exchange.response
            .bufferFactory()
            .wrap(body.toByteArray(StandardCharsets.UTF_8))

        return exchange.response.writeWith(Mono.just(buffer))
    }

    private fun resolveHttpStatus(ex: Throwable): HttpStatus {
        return when (ex) {
            is ResponseStatusException -> ex.statusCode as HttpStatus
            is TimeoutException -> HttpStatus.GATEWAY_TIMEOUT
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
    }

    private fun safeMessage(ex: Throwable): String {
        return when (ex) {
            is ResponseStatusException -> ex.reason ?: "Request failed"
            else -> "Unexpected internal error"
        }
    }

    private fun buildErrorResponse(
        exchange: ServerWebExchange,
        status: HttpStatus,
        message: String,
        traceId: String?
    ): String {
        return """
            {
              "timestamp": "${Instant.now()}",
              "status": ${status.value()},
              "error": "${status.reasonPhrase}",
              "message": "$message",
              "path": "${exchange.request.path}",
              "traceId": "$traceId"
            }
        """.trimIndent()
    }
}