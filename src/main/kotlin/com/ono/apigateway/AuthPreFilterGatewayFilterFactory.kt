package com.ono.apigateway

import com.ono.apigateway.redis.RedisRateLimiter
import io.jsonwebtoken.JwtException
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class AuthPreFilterGatewayFilterFactory(
    private val jwtTokenProvider: JwtTokenProvider,
    private val routeValidator: RouteValidator,
    private val rateLimiter: RedisRateLimiter,
    private val redisTemplate: ReactiveRedisTemplate<String, String>
) : AbstractGatewayFilterFactory<AuthPreFilterGatewayFilterFactory.Config>(Config::class.java) {

    private val log = LoggerFactory.getLogger(javaClass)

    class Config

    override fun apply(config: Config): GatewayFilter {
        return GatewayFilter { exchange, chain ->

            val request = exchange.request
            val ip = request.remoteAddress?.address?.hostAddress ?: "unknown"

            rateLimiter.isAllowed(ip, limit = 100, windowSeconds = 60)
                .flatMap { allowed ->
                    if (!allowed) {
                        return@flatMap onError(
                            exchange,
                            "Rate limit exceeded",
                            HttpStatus.TOO_MANY_REQUESTS
                        )
                    }

                    if (!routeValidator.isSecured(request.uri.path)) {
                        return@flatMap chain.filter(exchange)
                    }

                    val authHeader = request.headers.getFirst(HttpHeaders.AUTHORIZATION)
                        ?: return@flatMap onError(
                            exchange,
                            "Missing Authorization header",
                            HttpStatus.UNAUTHORIZED
                        )

                    if (!authHeader.startsWith("Bearer ")) {
                        return@flatMap onError(
                            exchange,
                            "Invalid Authorization header",
                            HttpStatus.UNAUTHORIZED
                        )
                    }

                    val token = authHeader.substring(7)

                    val jti = try {
                        jwtTokenProvider.extractJti(token)
                    } catch (ex: Exception) {
                        return@flatMap onError(
                            exchange,
                            "Invalid token",
                            HttpStatus.UNAUTHORIZED
                        )
                    }

                    redisTemplate.hasKey("blacklist:jti:$jti")
                        .flatMap { blacklisted ->
                            if (blacklisted) {
                                return@flatMap onError(
                                    exchange,
                                    "Token revoked",
                                    HttpStatus.UNAUTHORIZED
                                )
                            }

                            try {
                                jwtTokenProvider.validateToken(token)
                                val email = jwtTokenProvider.extractEmail(token)

                                val mutatedRequest = request.mutate()
                                    .header("X-User-Id", email)
                                    .build()

                                chain.filter(
                                    exchange.mutate().request(mutatedRequest).build()
                                )
                            } catch (ex: JwtException) {
                                onError(
                                    exchange,
                                    "Invalid or expired JWT",
                                    HttpStatus.UNAUTHORIZED
                                )
                            }
                        }
                }
        }
    }

    private fun onError(
        exchange: ServerWebExchange,
        message: String,
        status: HttpStatus
    ): Mono<Void> {
        log.warn(
            "Gateway security rejection: {} {}",
            exchange.request.uri.path,
            message
        )

        exchange.response.statusCode = status
        return exchange.response.setComplete()
    }
}
