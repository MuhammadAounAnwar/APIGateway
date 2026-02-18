package com.ono.apigateway.config

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
@Profile("prod")
class ProdResilienceConfig {

    // ============================================================
    // CIRCUIT BREAKER
    // ============================================================

    @Bean
    fun defaultCircuitBreakerCustomizer(): CircuitBreakerConfigCustomizer {
        return CircuitBreakerConfigCustomizer.of("default") { builder ->
            builder
                .failureRateThreshold(40f)              // stricter in prod
                .slowCallRateThreshold(40f)
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(5)
                .minimumNumberOfCalls(20)
                .slidingWindowSize(50)
        }
    }

    // ============================================================
    // RETRY
    // ============================================================

    @Bean
    fun defaultRetryCustomizer(): RetryConfigCustomizer {
        return RetryConfigCustomizer.of("default") { builder ->
            builder
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .intervalFunction(
                    IntervalFunction.ofExponentialBackoff(500, 2.0)
                )
                .retryExceptions(
                    java.io.IOException::class.java,
                    java.util.concurrent.TimeoutException::class.java
                )
        }
    }

    // ============================================================
    // BULKHEAD (Semaphore)
    // ============================================================

    @Bean
    fun defaultBulkheadCustomizer(): BulkheadConfigCustomizer {
        return BulkheadConfigCustomizer.of("default") { builder ->
            builder
                .maxConcurrentCalls(50)
                .maxWaitDuration(Duration.ofMillis(200))
        }
    }

    // ============================================================
    // THREAD POOL BULKHEAD (Optional Advanced Isolation)
    // ============================================================

    @Bean
    fun threadPoolBulkheadCustomizer(): io.github.resilience4j.common.bulkhead.configuration.ThreadPoolBulkheadConfigCustomizer {
        return io.github.resilience4j.common.bulkhead.configuration.ThreadPoolBulkheadConfigCustomizer
            .of("default") { builder ->
                builder
                    .coreThreadPoolSize(20)
                    .maxThreadPoolSize(50)
                    .queueCapacity(100)
            }
    }

    // ============================================================
    // TIME LIMITER
    // ============================================================

    @Bean
    fun defaultTimeLimiterCustomizer(): TimeLimiterConfigCustomizer {
        return TimeLimiterConfigCustomizer.of("default") { builder ->
            builder
                .timeoutDuration(Duration.ofSeconds(3))
                .cancelRunningFuture(true)
        }
    }


    @Bean
    fun orderStrictCircuitBreaker(): CircuitBreakerConfigCustomizer {
        return CircuitBreakerConfigCustomizer.of("orderStrict") { builder ->
            builder
                .failureRateThreshold(30f)              // stricter
                .slowCallRateThreshold(30f)
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .waitDurationInOpenState(Duration.ofSeconds(45))
                .minimumNumberOfCalls(30)
                .slidingWindowSize(100)
                .permittedNumberOfCallsInHalfOpenState(5)
        }
    }

    @Bean
    fun emailLenientCircuitBreaker(): CircuitBreakerConfigCustomizer {
        return CircuitBreakerConfigCustomizer.of("emailLenient") { builder ->
            builder
                .failureRateThreshold(70f)              // tolerate more failure
                .slowCallRateThreshold(70f)
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .minimumNumberOfCalls(5)
                .slidingWindowSize(10)
                .permittedNumberOfCallsInHalfOpenState(3)
        }
    }

    @Bean
    fun orderRetry(): RetryConfigCustomizer {
        return RetryConfigCustomizer.of("orderStrict") { builder ->
            builder
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(400))
        }
    }
}