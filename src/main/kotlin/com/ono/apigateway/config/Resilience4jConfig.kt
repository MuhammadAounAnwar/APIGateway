package com.ono.apigateway.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("default")
class Resilience4jConfig {

    @Bean
    fun circuitBreakerEventLogger(
        registry: CircuitBreakerRegistry,
        meterRegistry: MeterRegistry
    ): ApplicationRunner {
        return ApplicationRunner {
            val log = LoggerFactory.getLogger(javaClass)
            log.info("Initializing CircuitBreaker observers")

            registry.allCircuitBreakers.forEach { cb ->

                cb.eventPublisher
                    .onStateTransition { event ->
                        log.warn(
                            "Circuit '{}' changed state from {} to {}",
                            cb.name,
                            event.stateTransition.fromState,
                            event.stateTransition.toState
                        )
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