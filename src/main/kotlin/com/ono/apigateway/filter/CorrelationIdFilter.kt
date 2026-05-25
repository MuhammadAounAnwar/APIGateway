package com.ono.apigateway.filter

import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.core.Ordered
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

        val mutatedExchange = exchange.mutate()
            .request(exchange.request.mutate().header(HEADER, requestId).build())
            .build()

        return chain.filter(mutatedExchange).then(
            Mono.fromRunnable {
                mutatedExchange.response.headers.set(HEADER, requestId)
            }
        )
    }
}
