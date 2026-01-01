package com.ono.apigateway

import io.jsonwebtoken.JwtException
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class AuthPreFilterGatewayFilterFactory(
    val jwtTokenProvider: JwtTokenProvider,
    val routeValidator: RouteValidator
) : AbstractGatewayFilterFactory<AuthPreFilterGatewayFilterFactory.Config>(Config::class.java) {

    private val log = LoggerFactory.getLogger(AuthPreFilterGatewayFilterFactory::class.java)

    class Config

    override fun apply(config: Config?): GatewayFilter? {
        return GatewayFilter { exchange, chain ->

            val request = exchange.request
            val path = request.uri.path


            if (routeValidator.isSecured(path)) {
                val authHeader = request.headers.getFirst(HttpHeaders.AUTHORIZATION)
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    return@GatewayFilter chain.filter(exchange)
                }

                val token = authHeader.substring(7)
                return@GatewayFilter try {
                    jwtTokenProvider.validateToken(token)
                    val email = jwtTokenProvider.extractEmail(token)

                    val mutateRequest = request.mutate()
                        .header("X-User-Id", email)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                        .build()

                    chain.filter(exchange.mutate().request(mutateRequest).build())
                } catch (ex: JwtException) {
                    log.warn(
                        "JWT validation failed",
                        mapOf(
                            "path" to path,
                            "reason" to ex.message,
                            "ip" to request.remoteAddress?.address?.hostAddress
                        )
                    )

                    onError(exchange, "Invalid token", HttpStatus.UNAUTHORIZED)


                } catch (ex: Exception) {

                    log.warn(
                        "An internal error occurred",
                        mapOf(
                            "path" to path,
                            "reason" to ex.message,
                            "ip" to request.remoteAddress?.address?.hostAddress
                        )
                    )

                    onError(
                        exchange,
                        "An internal error occurred",
                        HttpStatus.INTERNAL_SERVER_ERROR
                    )
                }
            }

            chain.filter(exchange)
        }
    }

    private fun onError(exchange: ServerWebExchange, message: String, status: HttpStatus): Mono<Void> {
        log.error(
            "Gateway authentication error",
            mapOf(
                "status" to status.value(),
                "path" to exchange.request.uri.path,
                "message" to message
            )
        )

        val response = exchange.response
        response.statusCode = status
        return response.setComplete()
    }
}