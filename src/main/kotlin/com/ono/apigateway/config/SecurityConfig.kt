package com.ono.apigateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter
import reactor.core.publisher.Mono

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
class SecurityConfig(private val jwtDecoder: ReactiveJwtDecoder) {

    // Paths that must never trigger Bearer token validation — an invalid or absent
    // token on these paths must not produce a 401. Spring's BearerTokenAuthenticationWebFilter
    // runs before the permitAll() authorization decision, so we skip token extraction here.
    private val publicPathPrefixes = listOf(
        "/api/auth/",
        "/api/v1/public/",
        "/api/v1/guest/",
        "/ws/",
        "/fallback/",
        "/actuator/health",
        "/api-docs/",
        "/swagger-ui",
        "/swagger.yaml",
        "/webjars/",
        "/gateway-api-docs/"
    )

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {

        return http
            .csrf { it.disable() }
            .cors { }
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers("/api/auth/**").permitAll()
                    .pathMatchers("/api/v1/public/**").permitAll()
                    .pathMatchers("/api/v1/guest/**").permitAll()
                    .pathMatchers("/ws/**").permitAll()
                    .pathMatchers("/fallback/**").permitAll()
                    .pathMatchers("/actuator/health").permitAll()
                    .pathMatchers("/actuator/**").hasRole("ADMIN")
                    .pathMatchers(
                        "/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/swagger.yaml",
                        "/webjars/**",
                        "/gateway-api-docs/**"
                    ).permitAll()
                    .pathMatchers(HttpMethod.OPTIONS).permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/v1/venues").permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/v1/venues/*").permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/v1/venues/*/reviews").permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/v1/venues/*/courts").permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/v1/venues/*/courts/*/slots").permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/v1/courts").permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/v1/courts/**").permitAll()
                    .anyExchange().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.bearerTokenConverter(publicPathAwareBearerTokenConverter())
                oauth2.jwt { jwt ->
                    jwt.jwtDecoder(jwtDecoder)
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                }
            }
            .build()
    }

    // Returns Mono.empty() for public paths so BearerTokenAuthenticationWebFilter
    // skips validation entirely — preventing 401 when a stale/invalid token is present.
    @Bean
    fun publicPathAwareBearerTokenConverter(): ServerAuthenticationConverter {
        val delegate = ServerBearerTokenAuthenticationConverter()
        return ServerAuthenticationConverter { exchange ->
            val path = exchange.request.uri.path
            val isPublic = publicPathPrefixes.any { path.startsWith(it) } ||
                exchange.request.method == HttpMethod.OPTIONS
            if (isPublic) Mono.empty() else delegate.convert(exchange)
        }
    }

    @Bean
    fun jwtAuthenticationConverter(): ReactiveJwtAuthenticationConverterAdapter {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt ->
            val roles = jwt.getClaimAsStringList("roles") ?: emptyList()
            roles.map { role ->
                if (role.startsWith("ROLE_")) SimpleGrantedAuthority(role)
                else SimpleGrantedAuthority("ROLE_$role")
            }
        }
        return ReactiveJwtAuthenticationConverterAdapter(converter)
    }
}