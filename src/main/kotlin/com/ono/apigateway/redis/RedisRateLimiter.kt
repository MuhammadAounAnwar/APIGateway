package com.ono.apigateway.redis

import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class RedisRateLimiter(
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) {

    /**
     * Fixed-window rate limiting
     * key should already be fully constructed (ip or user based)
     */
    fun isAllowed(key: String, limit: Int, windowSeconds: Long): Mono<Boolean> {

        return redisTemplate
            .opsForValue()
            .increment(key)
            .flatMap { count ->
                if (count == 1L) {
                    // Set expiration only when key is first created
                    redisTemplate
                        .expire(key, Duration.ofSeconds(windowSeconds))
                        .thenReturn(count <= limit)
                } else {
                    Mono.just(count <= limit)
                }
            }
            .onErrorResume {
                // Fail-open strategy (do not block traffic if Redis fails)
                Mono.just(true)
            }
    }

    /**
     * Get remaining quota within current window
     */
    fun remainingQuota(key: String, limit: Int): Mono<Long> {
        return redisTemplate
            .opsForValue()
            .get(key)
            .map { value ->
                val used = value.toLongOrNull() ?: 0L
                (limit - used).coerceAtLeast(0L)
            }
            .defaultIfEmpty(limit.toLong())
    }

    /**
     * Reset rate limit manually (rare use case)
     */
    fun reset(key: String): Mono<Boolean> {
        return redisTemplate
            .delete(key)
            .map { it > 0 }
    }

    /**
     * Get remaining time-to-live for the window
     */
    fun getRemainingTtl(key: String): Mono<Duration> {
        return redisTemplate
            .getExpire(key)
            .filter { it > Duration.ofSeconds(0) }
            .map { it }
    }
}
