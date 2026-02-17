package com.ono.apigateway

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.*

@Component
@Order(-100) // High precedence (runs early)
class CorrelationIdFilter : GlobalFilter {

    companion object {
        private const val CORRELATION_ID_HEADER = "X-Correlation-Id"
    }

    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain
    ): Mono<Void> {

        // 1️⃣ Extract existing correlation ID (if provided by client)
        val existingCorrelationId =
            exchange.request.headers.getFirst(CORRELATION_ID_HEADER)

        // 2️⃣ Generate one if missing
        val correlationId = existingCorrelationId ?: UUID.randomUUID().toString()

        // 3️⃣ Mutate request to ensure header is present downstream
        val mutatedRequest = exchange.request.mutate()
            .headers { headers ->
                headers[CORRELATION_ID_HEADER] = listOf(correlationId)
            }
            .build()

        val mutatedExchange = exchange.mutate()
            .request(mutatedRequest)
            .build()

        // 4️⃣ Add correlation ID to response header as well
        mutatedExchange.response.headers.add(CORRELATION_ID_HEADER, correlationId)

        // 5️⃣ Continue filter chain
        return chain.filter(mutatedExchange)
    }
}