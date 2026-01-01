package com.ono.apigateway

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.*

@Component
@Order(-100)
class CorrelationIdFilter : GlobalFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain
    ): Mono<Void> {
        val correlationId = exchange.request.headers.getFirst("X-Correlation-Id")
            ?: UUID.randomUUID().toString()
        val request = exchange.request.mutate()
            .header("X-Correlation-Id", correlationId)
            .build()
        return chain.filter(exchange.mutate().request(request).build())
    }
}