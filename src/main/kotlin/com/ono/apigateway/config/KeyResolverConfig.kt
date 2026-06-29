package com.ono.apigateway.config

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Mono

@Configuration
class KeyResolverConfig {

    @Bean
    fun ipKeyResolver(): KeyResolver = KeyResolver { exchange ->
        val ip = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
        Mono.just(ip)
    }
}
