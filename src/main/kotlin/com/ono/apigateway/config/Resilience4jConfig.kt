package com.ono.apigateway.config

import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.common.bulkhead.configuration.BulkheadConfigCustomizer
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer
import io.github.resilience4j.common.timelimiter.configuration.TimeLimiterConfigCustomizer
import io.github.resilience4j.core.IntervalFunction
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Duration

@Configuration
@Profile("default")
class Resilience4jConfig {

    // ============================================================
    // CIRCUIT BREAKER
    // ============================================================

    @Bean
    fun defaultCircuitBreakerCustomizer(): CircuitBreakerConfigCustomizer {
        return CircuitBreakerConfigCustomizer
            .of("default") { builder ->
                builder
                    .failureRateThreshold(50f) // open circuit if 50% failures
                    .slowCallRateThreshold(50f)
                    .slowCallDurationThreshold(Duration.ofSeconds(3))
                    .waitDurationInOpenState(Duration.ofSeconds(10))
                    .permittedNumberOfCallsInHalfOpenState(5)
                    .minimumNumberOfCalls(10)
                    .slidingWindowSize(20)
                    .build()
            }
    }

    // ============================================================
    // RETRY
    // ============================================================

    @Bean
    fun defaultRetryCustomizer(): RetryConfigCustomizer {
        return RetryConfigCustomizer
            .of("default") { builder ->
                builder
                    .maxAttempts(3)
                    .waitDuration(Duration.ofMillis(300))
                    .intervalFunction(
                        IntervalFunction.ofExponentialBackoff(
                            300,
                            2.0
                        )
                    )
                    .retryExceptions(
                        java.io.IOException::class.java,
                        java.util.concurrent.TimeoutException::class.java
                    )
                    .build()
            }
    }

    // ============================================================
    // BULKHEAD (Semaphore)
    // ============================================================

    @Bean
    fun defaultBulkheadCustomizer(): BulkheadConfigCustomizer {
        return BulkheadConfigCustomizer
            .of("default") { builder: BulkheadConfig.Builder ->
                builder
                    .maxConcurrentCalls(50)
                    .maxWaitDuration(Duration.ofMillis(500))
                    .build()
            }
    }

    // ============================================================
    // THREAD POOL BULKHEAD (Optional Advanced Isolation)
    // ============================================================

    @Bean
    fun threadPoolBulkheadCustomizer(): io.github.resilience4j.common.bulkhead.configuration.ThreadPoolBulkheadConfigCustomizer {
        return io.github.resilience4j.common.bulkhead.configuration.ThreadPoolBulkheadConfigCustomizer
            .of("default") { builder: ThreadPoolBulkheadConfig.Builder ->
                builder
                    .coreThreadPoolSize(10)
                    .maxThreadPoolSize(20)
                    .queueCapacity(50)
                    .build()
            }
    }

    // ============================================================
    // TIME LIMITER
    // ============================================================

    @Bean
    fun defaultTimeLimiterCustomizer(): TimeLimiterConfigCustomizer {
        return TimeLimiterConfigCustomizer
            .of("default") { builder ->
                builder
                    .timeoutDuration(Duration.ofSeconds(4))
                    .cancelRunningFuture(true)
                    .build()
            }
    }

    @Bean
    fun circuitBreakerEventLogger(
        registry: CircuitBreakerRegistry,
        meterRegistry: MeterRegistry
    ): ApplicationRunner {
        return ApplicationRunner {
            registry.allCircuitBreakers.forEach { cb ->

                cb.eventPublisher
                    .onStateTransition { event ->
                        meterRegistry.counter(
                            "gateway.circuit.state.transition",
                            "circuit", cb.name,
                            "from", event.stateTransition.fromState.name,
                            "to", event.stateTransition.toState.name
                        ).increment()
                    }

                cb.eventPublisher
                    .onError {
                        meterRegistry.counter(
                            "gateway.circuit.errors",
                            "circuit", cb.name
                        ).increment()
                    }

                cb.eventPublisher
                    .onCallNotPermitted {
                        meterRegistry.counter(
                            "gateway.circuit.blocked",
                            "circuit", cb.name
                        ).increment()
                    }
            }
        }
    }
}