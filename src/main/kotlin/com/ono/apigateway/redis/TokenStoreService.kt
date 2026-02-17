package com.ono.apigateway.redis

import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class TokenStoreService(
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) {

    companion object {
        private const val BLACKLIST_VALUE = "1"
    }

    // ============================================================
    // JWT CACHE SECTION (jti -> userId)
    // ============================================================

    /**
     * Cache token (jti -> userId) if not already cached.
     * Fail-open: if Redis fails, request should continue.
     */
    fun cacheIfAbsent(jti: String, userId: String, ttlSeconds: Long): Mono<Boolean> {
        if (ttlSeconds <= 0) return Mono.just(false)

        return redisTemplate
            .opsForValue()
            .setIfAbsent(
                RedisKeys.jwtCacheByJti(jti),
                userId,
                Duration.ofSeconds(ttlSeconds)
            )
            .onErrorResume { Mono.just(false) } // fail-open
    }

    /**
     * Retrieve cached userId by jti.
     * Fail-open: if Redis fails, return empty.
     */
    fun getUserId(jti: String): Mono<String> {
        return redisTemplate
            .opsForValue()
            .get(RedisKeys.jwtCacheByJti(jti))
            .onErrorResume { Mono.empty() } // fail-open
    }

    /**
     * Remove cached token entry.
     */
    fun evictCache(jti: String): Mono<Boolean> {
        return redisTemplate
            .delete(RedisKeys.jwtCacheByJti(jti))
            .map { it > 0 }
            .onErrorReturn(false)
    }

    /**
     * Get remaining TTL of cached token.
     */
    fun getCacheTtl(jti: String): Mono<Duration> {
        return redisTemplate
            .getExpire(RedisKeys.jwtCacheByJti(jti))
            .filter { !it.isNegative && !it.isZero }
            .defaultIfEmpty(Duration.ZERO)
            .onErrorReturn(Duration.ZERO)
    }

    // ============================================================
    // BLACKLIST SECTION
    // ============================================================

    /**
     * Blacklist token (logout support).
     * Fail-safe: if Redis fails, better to reject access.
     */
    fun blacklist(jti: String, ttlSeconds: Long): Mono<Boolean> {
        if (ttlSeconds <= 0) return Mono.just(false)

        return redisTemplate
            .opsForValue()
            .set(
                RedisKeys.jwtBlacklist(jti),
                BLACKLIST_VALUE,
                Duration.ofSeconds(ttlSeconds)
            )
    }

    /**
     * Blacklist only if not already blacklisted.
     */
    fun blacklistIfAbsent(jti: String, ttlSeconds: Long): Mono<Boolean> {
        if (ttlSeconds <= 0) return Mono.just(false)

        return redisTemplate
            .opsForValue()
            .setIfAbsent(
                RedisKeys.jwtBlacklist(jti),
                BLACKLIST_VALUE,
                Duration.ofSeconds(ttlSeconds)
            )
    }

    /**
     * Check if token is blacklisted.
     * Fail-safe: if Redis fails, treat as blacklisted.
     */
    fun isBlacklisted(jti: String): Mono<Boolean> {
        return redisTemplate
            .hasKey(RedisKeys.jwtBlacklist(jti))
            .onErrorReturn(true) // fail-safe (security first)
    }

    /**
     * Remove token from blacklist manually.
     */
    fun evictBlacklist(jti: String): Mono<Boolean> {
        return redisTemplate
            .delete(RedisKeys.jwtBlacklist(jti))
            .map { it > 0 }
            .onErrorReturn(false)
    }

    /**
     * Get remaining TTL of blacklisted token.
     */
    fun getBlacklistTtl(jti: String): Mono<Duration> {
        return redisTemplate
            .getExpire(RedisKeys.jwtBlacklist(jti))
            .filter { !it.isNegative && !it.isZero }
            .defaultIfEmpty(Duration.ZERO)
            .onErrorReturn(Duration.ZERO)
    }
}