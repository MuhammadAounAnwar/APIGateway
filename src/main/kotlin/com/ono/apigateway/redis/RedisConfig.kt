package com.ono.apigateway.redis

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisConfig {

    @Bean
    fun reactiveRedisTemplate(
        factory: ReactiveRedisConnectionFactory
    ): ReactiveRedisTemplate<String, String> {

        val serializer = RedisSerializationContext
            .newSerializationContext<String, String>(StringRedisSerializer())
            .value(StringRedisSerializer())
            .build()

        return ReactiveRedisTemplate(factory, serializer)
    }
}