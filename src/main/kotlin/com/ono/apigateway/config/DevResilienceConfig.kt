package com.ono.apigateway.config

import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig
import io.github.resilience4j.common.bulkhead.configuration.BulkheadConfigCustomizer
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer
import io.github.resilience4j.common.timelimiter.configuration.TimeLimiterConfigCustomizer
import io.github.resilience4j.core.IntervalFunction
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Duration

@Configuration
@Profile("dev")
class DevResilienceConfig {

    @Bean
    fun defaultCircuitBreakerCustomizer(): CircuitBreakerConfigCustomizer {
        return CircuitBreakerConfigCustomizer.of("default") { builder ->
            builder
                .failureRateThreshold(70f)              // tolerate more failures in dev
                .slowCallRateThreshold(70f)
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(3)
                .minimumNumberOfCalls(5)
                .slidingWindowSize(10)
        }
    }

    @Bean
    fun defaultRetryCustomizer(): RetryConfigCustomizer {
        return RetryConfigCustomizer.of("default") { builder ->
            builder
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(200))
                .intervalFunction(
                    IntervalFunction.ofExponentialBackoff(200, 1.5)
                )
                .retryExceptions(
                    java.io.IOException::class.java,
                    java.util.concurrent.TimeoutException::class.java
                )
        }
    }

    @Bean
    fun defaultBulkheadCustomizer(): BulkheadConfigCustomizer {
        return BulkheadConfigCustomizer.of("default") { builder ->
            builder
                .maxConcurrentCalls(100)
                .maxWaitDuration(Duration.ofMillis(200))
        }
    }

    @Bean
    fun threadPoolBulkheadCustomizer(): io.github.resilience4j.common.bulkhead.configuration.ThreadPoolBulkheadConfigCustomizer {
        return io.github.resilience4j.common.bulkhead.configuration.ThreadPoolBulkheadConfigCustomizer
            .of("default") { builder ->
                builder
                    .coreThreadPoolSize(5)
                    .maxThreadPoolSize(10)
                    .queueCapacity(20)
            }
    }

    @Bean
    fun defaultTimeLimiterCustomizer(): TimeLimiterConfigCustomizer {
        return TimeLimiterConfigCustomizer.of("default") { builder ->
            builder
                .timeoutDuration(Duration.ofSeconds(6))
                .cancelRunningFuture(true)
        }
    }
}