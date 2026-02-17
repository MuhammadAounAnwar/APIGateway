package com.ono.apigateway

import com.ono.apigateway.redis.TokenStoreService
import com.ono.apigateway.redis.RedisKeys
import com.ono.apigateway.redis.RedisRateLimiter
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

class AuthPreFilterGatewayFilterFactory(
    private val routeValidator: RouteValidator,
    private val rateLimiter: RedisRateLimiter,
    private val tokenStoreService: TokenStoreService
) : AbstractGatewayFilterFactory<AuthPreFilterGatewayFilterFactory.Config>(Config::class.java) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val CORRELATION_ID_HEADER = "X-Correlation-Id"
        private const val USER_ID_HEADER = "X-User-Id"
        private const val USER_ROLES_HEADER = "X-User-Roles"
        private const val RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"
        private const val RATE_LIMIT_LIMIT = "X-RateLimit-Limit"
        private const val RATE_LIMIT_RESET = "X-RateLimit-Reset"
    }

    class Config

    override fun apply(config: Config): GatewayFilter {
        return GatewayFilter { exchange, chain ->

            val request = exchange.request
            val path = request.uri.path
            val ip = request.remoteAddress?.address?.hostAddress ?: "unknown"
            val correlationId = request.headers.getFirst(CORRELATION_ID_HEADER) ?: "UNKNOWN"

            // ---------------- PUBLIC ROUTES ----------------
            if (!routeValidator.isSecured(path)) {
                val key = RedisKeys.rateByIp(ip)
                return@GatewayFilter rateLimiter
                    .isAllowed(key, 50, 60)
                    .flatMap { allowed ->
                        if (!allowed) return@flatMap reject(exchange, HttpStatus.TOO_MANY_REQUESTS)

                        addRateLimitHeaders(exchange, key, 50)
                            .then(chain.filter(exchange))
                    }
            }

            // ---------------- SECURED ROUTES ----------------
            exchange.getPrincipal<Authentication>()
                .switchIfEmpty(reject(exchange, HttpStatus.UNAUTHORIZED).then(Mono.empty()))
                .cast(Authentication::class.java)
                .flatMap { authentication ->

                    val jwt = authentication.principal as? Jwt
                        ?: return@flatMap reject(exchange, HttpStatus.UNAUTHORIZED)

                    val userId = jwt.subject
                        ?: return@flatMap reject(exchange, HttpStatus.UNAUTHORIZED)

                    val jti = jwt.id
                        ?: return@flatMap reject(exchange, HttpStatus.UNAUTHORIZED)

                    val roles = authentication.authorities.joinToString(",") { it.authority }

                    val rateKey = RedisKeys.rateByUser(userId)

                    // Per-user rate limiting
                    rateLimiter.isAllowed(rateKey, 200, 60)
                        .flatMap { allowed ->
                            if (!allowed) return@flatMap reject(exchange, HttpStatus.TOO_MANY_REQUESTS)

                            // Blacklist check (fail-safe)
                            tokenStoreService.isBlacklisted(jti)
                                .flatMap { blacklisted ->
                                    if (blacklisted) return@flatMap reject(exchange, HttpStatus.UNAUTHORIZED)

                                    // Cache JTI until token expiration (fail-open)
                                    val ttlSeconds = jwt.expiresAt?.let {
                                        Duration.between(Instant.now(), it).seconds.coerceAtLeast(0)
                                    } ?: 0

                                    val cacheMono = if (ttlSeconds > 0) {
                                        tokenStoreService.cacheIfAbsent(jti, userId, ttlSeconds)
                                    } else Mono.empty()

                                    cacheMono.then(
                                        addRateLimitHeaders(exchange, rateKey, 200)
                                            .then(chain.filter(mutate(exchange, userId, roles, correlationId)))
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

    private fun addRateLimitHeaders(
        exchange: ServerWebExchange,
        key: String,
        limit: Int
    ): Mono<Void> {

        return rateLimiter
            .remainingQuota(key, limit)
            .zipWith(rateLimiter.getRemainingTtl(key).defaultIfEmpty(Duration.ZERO))
            .doOnNext { tuple ->
                val remaining = tuple.t1
                val ttl = tuple.t2

                val resetEpoch = Instant.now()
                    .plusSeconds(ttl.seconds)
                    .epochSecond

                exchange.response.headers.set(RATE_LIMIT_LIMIT, limit.toString())
                exchange.response.headers.set(RATE_LIMIT_REMAINING, remaining.toString())
                exchange.response.headers.set(RATE_LIMIT_RESET, resetEpoch.toString())
            }
            .then()
    }

    private fun reject(exchange: ServerWebExchange, status: HttpStatus): Mono<Void> {

        val correlationId = exchange.request.headers
            .getFirst(CORRELATION_ID_HEADER) ?: "UNKNOWN"

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
        exchange.response.headers.contentType = org.springframework.http.MediaType.APPLICATION_JSON

        val body = """
            {
              "timestamp": "${Instant.now()}",
              "status": ${status.value()},
              "error": "${status.reasonPhrase}",
              "path": "${exchange.request.uri.path}",
              "correlationId": "$correlationId"
            }
        """.trimIndent()

        val buffer = exchange.response.bufferFactory().wrap(body.toByteArray())
        return exchange.response.writeWith(Mono.just(buffer))
    }
}
