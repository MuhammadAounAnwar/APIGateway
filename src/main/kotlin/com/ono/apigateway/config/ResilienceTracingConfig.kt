package com.ono.apigateway.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.tracing.Tracer
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ResilienceTracingConfig(
    private val tracer: Tracer,
    private val retryRegistry: RetryRegistry
) {

    @PostConstruct
    fun registerRetryTracing() {
        retryRegistry.allRetries.forEach { retry ->
            retry.eventPublisher.onRetry { event ->
                val parentSpan = tracer.currentSpan()
                val span = tracer.nextSpan(parentSpan)
                    .name("resilience4j.retry")
                    .start()

                try {
                    span.tag("retry.name", event.name)
                    span.tag("retry.attempt", event.numberOfRetryAttempts.toString())
                    span.tag(
                        "retry.exception",
                        event.lastThrowable?.javaClass?.simpleName ?: "unknown"
                    )
                } finally {
                    span.end()
                }
            }
        }
    }

    @Bean
    fun circuitBreakerEventListener(
        circuitBreakerRegistry: CircuitBreakerRegistry
    ): ApplicationRunner {
        return ApplicationRunner {

            circuitBreakerRegistry.allCircuitBreakers.forEach { cb ->

                cb.eventPublisher
                    .onStateTransition { event ->
                        val parentSpan = tracer.currentSpan()
                        val span = tracer.nextSpan(parentSpan)
                            .name("resilience4j.circuitbreaker.state.transition")
                            .start()
                        try {
                            span.tag("circuitbreaker.name", cb.name)
                            span.tag("circuitbreaker.from", event.stateTransition.fromState.name)
                            span.tag("circuitbreaker.to", event.stateTransition.toState.name)

                            logEvent(
                                "CB_STATE_CHANGE",
                                cb.name,
                                event.stateTransition.toString()
                            )
                        } finally {
                            span.end()
                        }
                    }
                    .onError { event ->
                        val parentSpan = tracer.currentSpan()
                        val span = tracer.nextSpan(parentSpan)
                            .name("resilience4j.circuitbreaker.error")
                            .start()
                        try {
                            span.tag("circuitbreaker.name", cb.name)
                            span.tag(
                                "circuitbreaker.exception",
                                event.throwable.javaClass.simpleName
                            )

                            logEvent(
                                "CB_ERROR",
                                cb.name,
                                event.throwable.message
                            )
                        } finally {
                            span.end()
                        }
                    }
            }
        }
    }

    private fun logEvent(type: String, name: String, detail: String?) {
        val traceId = MDC.get("traceId") ?: "N/A"
        LoggerFactory.getLogger("CircuitBreakerEvents")
            .info("[$type] breaker=$name traceId=$traceId detail=$detail")
    }
}