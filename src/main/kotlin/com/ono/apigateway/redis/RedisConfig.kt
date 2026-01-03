package com.ono.apigateway.redis

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisConfig {

    // -------------------------------------------
    // 1. Rate Limiting Redis Template
    // -------------------------------------------
    @Bean("rateLimitRedisTemplate")
    fun rateLimitRedisTemplate(
        factory: ReactiveRedisConnectionFactory
    ): ReactiveRedisTemplate<String, String> {

        val context = RedisSerializationContext
            .newSerializationContext<String, String>(StringRedisSerializer())
            .value(StringRedisSerializer())
            .build()

        return ReactiveRedisTemplate(factory, context)
    }

    // -------------------------------------------
    // 2. Cache / JWT / Blacklist Redis Template
    // -------------------------------------------
    @Bean("cacheRedisTemplate")
    fun cacheRedisTemplate(
        factory: ReactiveRedisConnectionFactory
    ): ReactiveRedisTemplate<String, String> {

        val context = RedisSerializationContext
            .newSerializationContext<String, String>(StringRedisSerializer())
            .value(StringRedisSerializer())
            .build()

        return ReactiveRedisTemplate(factory, context)
    }
}
