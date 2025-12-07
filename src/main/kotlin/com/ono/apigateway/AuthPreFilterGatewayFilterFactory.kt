package com.ono.apigateway

import io.jsonwebtoken.JwtException
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

    class Config

    override fun apply(config: Config?): GatewayFilter? {
        return GatewayFilter { exchange, chain ->

            val request = exchange.request
            val path = request.uri.path


            if (routeValidator.isSecured(path)) {
                val authHeader = request.headers.getFirst("Authorization")
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    return@GatewayFilter chain.filter(exchange)
                }

                val token = authHeader.substring(7)
                try {
                    jwtTokenProvider.validateToken(token)
                    val email = jwtTokenProvider.extractEmail(token)

                    val mutateRequest = request.mutate()
                        .header("X-User-Id", email)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                        .build()

                    return@GatewayFilter chain.filter(exchange.mutate().request(mutateRequest).build())
                } catch (ex: JwtException) {
                    return@GatewayFilter onError(exchange, "JWT invalid token", HttpStatus.UNAUTHORIZED)
                } catch (e: Exception) {
                    return@GatewayFilter onError(
                        exchange,
                        "An internal error occurred",
                        HttpStatus.INTERNAL_SERVER_ERROR
                    )
                }
            }

            chain.filter(exchange)
        }
    }

    private fun onError(exchange: ServerWebExchange, err: String, status: HttpStatus): Mono<Void> {
        val response = exchange.response
        response.statusCode = status

        println("Gateway Filter Error: $err")
        return response.setComplete()
    }
}