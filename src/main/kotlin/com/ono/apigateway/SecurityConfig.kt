package com.ono.apigateway

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
class SecurityConfig {

    /**
     * Main security configuration for API Gateway.
     *
     * Responsibilities:
     * - Validate JWT using OAuth2 Resource Server
     * - Permit public auth endpoints
     * - Secure all other routes
     */
    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {

        return http
            .csrf { it.disable() }
            .cors { } // Uses gateway CORS config
            .authorizeExchange { exchanges ->

                exchanges
                    // Public endpoints (Authentication Service)
                    .pathMatchers("/api/auth/**").permitAll()

                    // Allow preflight requests
                    .pathMatchers(HttpMethod.OPTIONS).permitAll()

                    // Everything else requires authentication
                    .anyExchange().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                }
            }
            .build()
    }

    /**
     * Converts JWT claims into Spring Security authorities.
     * You can map roles from claims here (e.g., "roles", "authorities").
     */
    @Bean
    fun jwtAuthenticationConverter(): ReactiveJwtAuthenticationConverterAdapter {

        val converter = JwtAuthenticationConverter()

        converter.setJwtGrantedAuthoritiesConverter { jwt ->
            val roles = jwt.getClaimAsStringList("roles") ?: emptyList()

            roles.map { role ->
                org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_$role")
            }
        }

        return ReactiveJwtAuthenticationConverterAdapter(converter)
    }
}