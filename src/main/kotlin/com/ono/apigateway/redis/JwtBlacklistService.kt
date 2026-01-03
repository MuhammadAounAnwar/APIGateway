package com.ono.apigateway.redis

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class JwtBlacklistService(
    @Qualifier("cacheRedisTemplate")
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) {

    fun blacklist(jti: String, ttlSeconds: Long): Mono<Boolean> {
        val key = RedisKeys.jwtBlacklist(jti)
        return redisTemplate.opsForValue()
            .set(key, "true", Duration.ofSeconds(ttlSeconds))
    }

    fun isBlacklisted(jti: String): Mono<Boolean> {
        val key = RedisKeys.jwtBlacklist(jti)
        return redisTemplate.hasKey(key)
    }
}
