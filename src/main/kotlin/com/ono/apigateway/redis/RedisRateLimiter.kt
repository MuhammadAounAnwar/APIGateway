package com.ono.apigateway.redis

import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class RedisRateLimiter(
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) {
    fun isAllowed(key: String, limit: Int, windowSeconds: Long): Mono<Boolean> {
        val redisKey = RedisKeys.rateByIp(key)

        return redisTemplate.opsForValue()
            .increment(redisKey)
            .flatMap { count ->
                if (count == 1L) {
                    redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds))
                }
                Mono.just(count <= limit)
            }
    }
}
