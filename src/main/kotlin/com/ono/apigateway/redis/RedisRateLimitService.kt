package com.ono.apigateway.redis

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class RedisRateLimitService(
    @Qualifier("rateLimitRedisTemplate")
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val rateLimiterScript: RedisScript<Long>
) {

    fun isAllowed(key: String, limit: Int, windowSec: Int): Mono<Boolean> {

        return redisTemplate.execute(
            rateLimiterScript,
            listOf(key),
            listOf(limit.toString(), windowSec.toString())
        )
            .next()
            .map { it == 1L }
            .defaultIfEmpty(false)
    }

}
