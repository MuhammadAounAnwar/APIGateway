package com.ono.apigateway.filter

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Blocks all requests whose path starts with /internal/.
 *
 * Internal service-to-service endpoints are not routed through the Gateway;
 * this filter provides an explicit 403 guard so a future misconfigured route
 * cannot accidentally expose them to external callers.
 *
 * Order -200: runs after CorrelationIdFilter (-300) and AppCheckFilter (-250),
 * before any route-level filters.
 */
@Component
class InternalPathBlockFilter : GlobalFilter, Ordered {

    override fun getOrder(): Int = -200

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        if (path.startsWith("/internal/")) {
            val response = exchange.response
            response.statusCode = HttpStatus.FORBIDDEN
            response.headers.contentType = MediaType.APPLICATION_JSON
            val body = """{"error":"FORBIDDEN","message":"Access to /internal paths is not permitted via the API Gateway."}"""
            val buffer = response.bufferFactory().wrap(body.toByteArray(Charsets.UTF_8))
            return response.writeWith(Mono.just(buffer))
        }
        return chain.filter(exchange)
    }
}
