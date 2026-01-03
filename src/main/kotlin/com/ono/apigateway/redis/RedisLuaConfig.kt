package com.ono.apigateway.redis

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.script.RedisScript

@Configuration
class RedisLuaConfig {

    @Bean
    fun rateLimiterScript(): RedisScript<Long> =
        RedisScript.of(
            """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])

            local current = redis.call("INCR", key)
            if current == 1 then
                redis.call("EXPIRE", key, window)
            end

            if current > limit then
                return 0
            end

            return 1
            """.trimIndent(),
            Long::class.java
        )
}
