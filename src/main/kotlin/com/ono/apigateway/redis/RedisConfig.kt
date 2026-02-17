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

        val keySerializer = StringRedisSerializer()
        val valueSerializer = StringRedisSerializer()

        val context = RedisSerializationContext
            .newSerializationContext<String, String>(keySerializer)
            .key(keySerializer)
            .value(valueSerializer)
            .hashKey(keySerializer)
            .hashValue(valueSerializer)
            .build()

        return ReactiveRedisTemplate(factory, context)
    }
}
