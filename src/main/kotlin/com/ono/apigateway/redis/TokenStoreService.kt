package com.ono.apigateway.redis

import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration
import org.slf4j.LoggerFactory

@Component
class TokenStoreService(
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) {

    private val log = LoggerFactory.getLogger(TokenStoreService::class.java)

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
            .doOnError { ex ->
                log.error("Redis JWT cache failure for jti={}", jti, ex)
            }
    }

    /**
     * Retrieve cached userId by jti.
     * Fail-open: if Redis fails, return empty.
     */
    fun getUserId(jti: String): Mono<String> {
        return redisTemplate
            .opsForValue()
            .get(RedisKeys.jwtCacheByJti(jti))
            .doOnError { ex ->
                log.error("Redis JWT getUserId failure for jti={}", jti, ex)
            }
    }

    /**
     * Remove cached token entry.
     */
    fun evictCache(jti: String): Mono<Boolean> {
        return redisTemplate
            .delete(RedisKeys.jwtCacheByJti(jti))
            .map { it > 0 }
            .doOnError { ex ->
                log.error("Redis JWT evictCache failure for jti={}", jti, ex)
            }
    }

    /**
     * Get remaining TTL of cached token.
     */
    fun getCacheTtl(jti: String): Mono<Duration> {
        return redisTemplate
            .getExpire(RedisKeys.jwtCacheByJti(jti))
            .filter { !it.isNegative && !it.isZero }
            .defaultIfEmpty(Duration.ZERO)
            .doOnError { ex ->
                log.error("Redis JWT getCacheTtl failure for jti={}", jti, ex)
            }
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
            .doOnError { ex ->
                log.error("Redis blacklist check failure for jti={}", jti, ex)
            }
            .onErrorReturn(true) // security fail-safe (explicit + logged)
    }

    /**
     * Remove token from blacklist manually.
     */
    fun evictBlacklist(jti: String): Mono<Boolean> {
        return redisTemplate
            .delete(RedisKeys.jwtBlacklist(jti))
            .map { it > 0 }
            .doOnError { ex ->
                log.error("Redis evictBlacklist failure for jti={}", jti, ex)
            }
    }

    /**
     * Get remaining TTL of blacklisted token.
     */
    fun getBlacklistTtl(jti: String): Mono<Duration> {
        return redisTemplate
            .getExpire(RedisKeys.jwtBlacklist(jti))
            .filter { !it.isNegative && !it.isZero }
            .defaultIfEmpty(Duration.ZERO)
            .doOnError { ex ->
                log.error("Redis getBlacklistTtl failure for jti={}", jti, ex)
            }
    }
}