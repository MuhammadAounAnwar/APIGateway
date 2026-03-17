package com.ono.apigateway.filter

import com.ono.apigateway.redis.RedisKeys
import com.ono.apigateway.redis.GatewayRateLimiter
import com.ono.apigateway.redis.TokenStoreService
import com.ono.apigateway.security.RouteValidator
import com.ono.apigateway.util.*
import io.micrometer.tracing.Tracer
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

@Component
class AuthPreFilterGatewayFilterFactory(
    private val routeValidator: RouteValidator,
    private val rateLimiter: GatewayRateLimiter,
    private val tokenStoreService: TokenStoreService,
    private val tracer: Tracer
) : AbstractGatewayFilterFactory<AuthPreFilterGatewayFilterFactory.Config>(Config::class.java) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val USER_ID_HEADER = "X-User-Id"
        private const val USER_ROLES_HEADER = "X-User-Roles"
        private const val TENANT_ID_HEADER = "X-Tenant-ID"
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

            // ---------------- PUBLIC ROUTES ----------------
            if (!routeValidator.isSecured(path)) {
                val key = RedisKeys.rateByIp(ip)
                return@GatewayFilter rateLimiter
                    .isAllowed(key, 50, 60)
                    .flatMap { allowed ->

                        tracer.tagRateLimit("ip", key, allowed)

                        if (!allowed) return@flatMap tooManyRequests()

                        addRateLimitHeaders(exchange, key, 50)
                            .then(chain.filter(exchange))
                    }
            }

            // ---------------- SECURED ROUTES ----------------
            exchange.requireAuthContext()
                .flatMap { authContext ->

                    val jwt = authContext.jwt
                    val roles = authContext.roles

                    val userId = jwt.subject ?: return@flatMap unauthorized()
                    val jti = jwt.id ?: return@flatMap unauthorized()

                    tracer.event("authentication.success")
                    tracer.tag("auth.user.id", userId)
                    tracer.tag("auth.roles", roles)

                    val rateKey = RedisKeys.rateByUser(userId)

                    rateLimiter.isAllowed(rateKey, 200, 60)
                        .flatMap { allowed ->

                            tracer.tagRateLimit("user", rateKey, allowed)
                            tracer.tag("auth.user.id", userId)

                            if (!allowed) return@flatMap tooManyRequests()

                            tokenStoreService.isBlacklisted(jti)
                                .flatMap { blacklisted ->

                                    if (blacklisted) {
                                        tracer.event("token.blacklisted")
                                        tracer.tag("auth.user.id", userId)
                                        return@flatMap unauthorized("Token blacklisted")
                                    }

                                    val ttlSeconds = jwt.expiresAt?.let {
                                        Duration.between(Instant.now(), it).seconds.coerceAtLeast(0)
                                    } ?: 0

                                    val cacheMono = if (ttlSeconds > 0) {
                                        tokenStoreService.cacheIfAbsent(jti, userId, ttlSeconds)
                                    } else Mono.empty()

                                    val tenantId = jwt.getClaim<String>("tenantId") ?: "default"

                                    cacheMono.then(
                                        addRateLimitHeaders(exchange, rateKey, 200)
                                            .then(chain.filter(mutate(exchange, userId, roles, tenantId)))
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
        tenantId: String
    ): ServerWebExchange {
        return exchange.mutate()
            .request(
                exchange.request.mutate()
                    .header(USER_ID_HEADER, userId)
                    .header(USER_ROLES_HEADER, roles)
                    .header(TENANT_ID_HEADER, tenantId)
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
}