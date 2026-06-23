package com.ono.apigateway.filter

import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * Ensures every request carries an X-Request-ID header.
 * If the client sends one it is forwarded as-is (allows end-to-end trace correlation).
 * If absent, a fresh UUID is generated and attached before downstream routing.
 * The same ID is echoed back in the response header.
 *
 * Order -300: runs before the auth filter (-200) and rate limiter.
 *
 * Note: Uses ServerHttpRequestDecorator to inject the header without mutating ReadOnlyHttpHeaders
 * (required for Spring Framework 6.2 compatibility — request mutation API changed behavior).
 */
@Component
class CorrelationIdFilter : GlobalFilter, Ordered {

    companion object {
        const val HEADER = "X-Request-ID"
    }

    override fun getOrder(): Int = -300

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val requestId = exchange.request.headers.getFirst(HEADER)
            ?: UUID.randomUUID().toString()

        // Echo the correlation ID back to the client before response is committed
        exchange.response.beforeCommit {
            exchange.response.headers.set(HEADER, requestId)
            Mono.empty()
        }

        // Decorator-based request wrapper: creates a fresh mutable HttpHeaders copy
        // with the correlation ID injected, avoiding ReadOnlyHttpHeaders mutation issues
        val decoratedRequest = object : ServerHttpRequestDecorator(exchange.request) {
            override fun getHeaders(): HttpHeaders {
                val headers = HttpHeaders()
                super.getHeaders().forEach { name, values ->
                    headers[name] = ArrayList(values)
                }
                headers.set(HEADER, requestId)
                return HttpHeaders.readOnlyHttpHeaders(headers)
            }
        }

        return chain.filter(exchange.mutate().request(decoratedRequest).build())
    }
}
