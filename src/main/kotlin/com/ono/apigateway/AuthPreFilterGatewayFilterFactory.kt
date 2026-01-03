package com.ono.apigateway

import com.ono.apigateway.redis.JwtBlacklistService
import com.ono.apigateway.redis.JwtCacheService
import com.ono.apigateway.redis.RedisRateLimitService
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class AuthPreFilterGatewayFilterFactory(
    private val jwtTokenProvider: JwtTokenProvider,
    private val routeValidator: RouteValidator,
    private val rateLimitService: RedisRateLimitService,
    private val blacklistService: JwtBlacklistService,
    private val jwtCacheService: JwtCacheService
) : AbstractGatewayFilterFactory<AuthPreFilterGatewayFilterFactory.Config>(Config::class.java) {

    private val log = LoggerFactory.getLogger(javaClass)

    class Config

    override fun apply(config: Config): GatewayFilter {
        return GatewayFilter { exchange, chain ->

            val request = exchange.request
            val path = request.uri.path
            val ip = request.remoteAddress?.address?.hostAddress ?: "unknown"

            // 1️⃣ Rate limiting
            rateLimitService.isAllowed(ip, 100, 60)
                .flatMap { allowed ->
                    if (!allowed) return@flatMap reject(exchange, HttpStatus.TOO_MANY_REQUESTS)

                    // 2️⃣ Public routes
                    if (!routeValidator.isSecured(path)) {
                        return@flatMap chain.filter(exchange)
                    }

                    val token = extractToken(exchange)
                        ?: return@flatMap reject(exchange, HttpStatus.UNAUTHORIZED)

                    val jti = runCatching { jwtTokenProvider.extractJti(token) }
                        .getOrElse { return@flatMap reject(exchange, HttpStatus.UNAUTHORIZED) }

                    // 3️⃣ Blacklist check
                    blacklistService.isBlacklisted(jti)
                        .flatMap { blacklisted ->
                            if (blacklisted) return@flatMap reject(exchange, HttpStatus.UNAUTHORIZED)

                            // 4️⃣ Cache lookup
                            jwtCacheService.getCachedUser(jti)
                                .flatMap { cachedUser ->
                                    if (cachedUser != null) {
                                        return@flatMap chain.filter(mutate(exchange, cachedUser))
                                    }

                                    // 5️⃣ Full validation
                                    validateAndCache(exchange, chain, token, jti)
                                }
                        }
                }
        }
    }

    // ---------------- Helper Methods ----------------

    private fun validateAndCache(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain,
        token: String,
        jti: String
    ): Mono<Void> {
        return try {
            jwtTokenProvider.validateToken(token)
            val userId = jwtTokenProvider.extractEmail(token)
            val ttl = jwtTokenProvider.getRemainingValiditySeconds(token)

            jwtCacheService.cache(jti, userId, ttl)
                .then(chain.filter(mutate(exchange, userId)))
        } catch (ex: Exception) {
            reject(exchange, HttpStatus.UNAUTHORIZED)
        }
    }

    private fun extractToken(exchange: ServerWebExchange): String? {
        val header = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        return if (header?.startsWith("Bearer ") == true) header.substring(7) else null
    }

    private fun mutate(exchange: ServerWebExchange, userId: String): ServerWebExchange {
        return exchange.mutate()
            .request(
                exchange.request.mutate()
                    .header("X-User-Id", userId)
                    .build()
            )
            .build()
    }

    private fun reject(exchange: ServerWebExchange, status: HttpStatus): Mono<Void> {
        log.warn("Gateway rejected: {} {}", status, exchange.request.uri.path)
        exchange.response.statusCode = status
        return exchange.response.setComplete()
    }
}

