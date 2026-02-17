package com.ono.apigateway

import com.ono.apigateway.redis.JwtBlacklistService
import com.ono.apigateway.redis.JwtCacheService
import com.ono.apigateway.redis.RedisRateLimitService
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

class AuthPreFilterGatewayFilterFactory(
    private val routeValidator: RouteValidator,
    private val rateLimitService: RedisRateLimitService,
    private val blacklistService: JwtBlacklistService,
    private val jwtCacheService: JwtCacheService
) : AbstractGatewayFilterFactory<AuthPreFilterGatewayFilterFactory.Config>(Config::class.java) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val CORRELATION_ID_HEADER = "X-Correlation-Id"
        private const val USER_ID_HEADER = "X-User-Id"
        private const val USER_ROLES_HEADER = "X-User-Roles"
    }

    class Config

    override fun apply(config: Config): GatewayFilter {
        return GatewayFilter { exchange, chain ->

            val request = exchange.request
            val path = request.uri.path
            val ip = request.remoteAddress?.address?.hostAddress ?: "unknown"

            // CorrelationId is guaranteed by CorrelationIdFilter (GlobalFilter)
            val correlationId =
                request.headers.getFirst(CORRELATION_ID_HEADER) ?: "UNKNOWN"

            // 1️⃣ Public routes → rate limit by IP (anonymous traffic)
            if (!routeValidator.isSecured(path)) {
                return@GatewayFilter rateLimitService
                    .isAllowed("ip:$ip", 50, 60)
                    .flatMap { allowed ->
                        if (!allowed) {
                            reject(exchange, HttpStatus.TOO_MANY_REQUESTS)
                        } else {
                            chain.filter(exchange)
                        }
                    }
            }

            // 3️⃣ JWT already validated by Spring Security
            exchange.getPrincipal<Authentication>()
                .switchIfEmpty(reject(exchange, HttpStatus.UNAUTHORIZED).then(Mono.empty()))
                .cast(Authentication::class.java)
                .flatMap { authentication ->

                    val principal = authentication.principal

                    if (principal !is org.springframework.security.oauth2.jwt.Jwt) {
                        return@flatMap reject(exchange, HttpStatus.UNAUTHORIZED)
                    }

                    val userId = principal.subject
                        ?: return@flatMap reject(exchange, HttpStatus.UNAUTHORIZED)

                    // 2️⃣ Per-user rate limiting (authenticated traffic)
                    return@flatMap rateLimitService
                        .isAllowed("user:$userId", 200, 60)
                        .flatMap { allowed ->
                            if (!allowed) {
                                return@flatMap reject(exchange, HttpStatus.TOO_MANY_REQUESTS)
                            }

                            val roles = authentication.authorities
                                .joinToString(",") { it.authority }

                            val jti = principal.id
                                ?: return@flatMap reject(exchange, HttpStatus.UNAUTHORIZED)

                            // 3️⃣ Blacklist check (logout invalidation support)
                            blacklistService.isBlacklisted(jti)
                                .flatMap { blacklisted ->
                                    if (blacklisted) {
                                        return@flatMap reject(exchange, HttpStatus.UNAUTHORIZED)
                                    }

                                    // 4️⃣ Cache token until expiration (dynamic TTL)
                                    val expiresAt = principal.expiresAt
                                    val ttlSeconds = expiresAt?.let {
                                        java.time.Duration.between(
                                            java.time.Instant.now(),
                                            it
                                        ).seconds.coerceAtLeast(0)
                                    } ?: 0

                                    jwtCacheService.cache(jti, userId, ttlSeconds)
                                        .then(
                                            chain.filter(
                                                mutate(exchange, userId, roles, correlationId)
                                            )
                                        )
                                }
                        }
                }
        }
    }

    // ---------------- Helper Methods ----------------

    private fun mutate(
        exchange: ServerWebExchange,
        userId: String,
        roles: String,
        correlationId: String
    ): ServerWebExchange {

        return exchange.mutate()
            .request(
                exchange.request.mutate()
                    .header(USER_ID_HEADER, userId)
                    .header(USER_ROLES_HEADER, roles)
                    .header(CORRELATION_ID_HEADER, correlationId)
                    .build()
            )
            .build()
    }

    private fun reject(exchange: ServerWebExchange, status: HttpStatus): Mono<Void> {

        val correlationId =
            exchange.request.headers.getFirst(CORRELATION_ID_HEADER) ?: "UNKNOWN"

        log.warn(
            "Gateway rejection -> status: {}, method: {}, path: {}, ip: {}, correlationId: {}",
            status.value(),
            exchange.request.method,
            exchange.request.uri.path,
            exchange.request.remoteAddress?.address?.hostAddress ?: "unknown",
            correlationId
        )

        exchange.response.statusCode = status
        exchange.response.headers.add(CORRELATION_ID_HEADER, correlationId)
        exchange.response.headers.contentType =
            org.springframework.http.MediaType.APPLICATION_JSON

        val body = """
            {
              "timestamp": "${java.time.Instant.now()}",
              "status": ${status.value()},
              "error": "${status.reasonPhrase}",
              "path": "${exchange.request.uri.path}",
              "correlationId": "$correlationId"
            }
        """.trimIndent()

        val buffer = exchange.response.bufferFactory()
            .wrap(body.toByteArray())

        return exchange.response.writeWith(Mono.just(buffer))
    }
}
