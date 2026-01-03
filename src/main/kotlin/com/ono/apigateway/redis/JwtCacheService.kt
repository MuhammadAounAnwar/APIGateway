package com.ono.apigateway.redis

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class JwtCacheService(
    @Qualifier("cacheRedisTemplate")
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) {

    fun cache(token: String, userId: String, ttlSeconds: Long): Mono<Boolean> {
        val key = RedisKeys.jwtCache(token)
        return redisTemplate.opsForValue()
            .set(key, userId, Duration.ofSeconds(ttlSeconds))
    }

    fun getUserId(token: String): Mono<String?> {
        val key = RedisKeys.jwtCache(token)
        return redisTemplate.opsForValue().get(key)
    }

    fun getCachedUser(jti: String): Mono<String?> =
        redisTemplate.opsForValue().get(RedisKeys.jtiKey(jti))
}
