package com.ono.apigateway.redis

import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class RedisRateLimitService(
    private val reactiveRedisTemplate: ReactiveRedisTemplate<String, String>,
    private val rateLimiterScript: RedisScript<Long>
) {

    fun isAllowed(key: String, limit: Int, windowSec: Int): Mono<Boolean> {

        return reactiveRedisTemplate.execute(
            rateLimiterScript,
            listOf(key),
            listOf(limit.toString(), windowSec.toString())
        )
            .next()
            .map { it == 1L }
            .defaultIfEmpty(false)
    }

}
