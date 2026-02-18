package com.ono.apigateway.redis

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class RedisRateLimiter(
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) {

    private val log = LoggerFactory.getLogger(RedisRateLimiter::class.java)

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
                    redisTemplate
                        .expire(key, Duration.ofSeconds(windowSeconds))
                        .thenReturn(count <= limit)
                } else {
                    Mono.just(count <= limit)
                }
            }
            .doOnError { ex ->
                log.error("Redis rate limiter failure for key={}", key, ex)
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
            .doOnError { ex ->
                log.error("Redis remainingQuota failure for key={}", key, ex)
            }
    }

    /**
     * Reset rate limit manually (rare use case)
     */
    fun reset(key: String): Mono<Boolean> {
        return redisTemplate
            .delete(key)
            .map { it > 0 }
            .doOnError { ex ->
                log.error("Redis reset failure for key={}", key, ex)
            }
    }

    /**
     * Get remaining time-to-live for the window
     */
    fun getRemainingTtl(key: String): Mono<Duration> {
        return redisTemplate
            .getExpire(key)
            .filter { it > Duration.ofSeconds(0) }
            .doOnError { ex ->
                log.error("Redis TTL lookup failure for key={}", key, ex)
            }
    }
}
