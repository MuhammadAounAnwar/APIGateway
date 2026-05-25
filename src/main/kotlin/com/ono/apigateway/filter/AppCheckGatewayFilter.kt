package com.ono.apigateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Validates Firebase App Check tokens on every request.
 * Only active when FIREBASE_SERVICE_ACCOUNT_JSON is set (FirebaseApp bean is present).
 *
 * Token verification calls the Firebase App Check REST API:
 *   POST https://firebaseappcheck.googleapis.com/v1/projects/{projectId}:verifyAppCheckToken
 *
 * The blocking HTTP call is isolated on Schedulers.boundedElastic() so it never
 * blocks the Netty event loop.
 *
 * Exempt paths: /actuator/, /api/auth/, /api/v1/guest/, /fallback/, /ws/,
 *               /swagger, /api-docs, /webjars/, /gateway-api-docs/
 *
 * Order -250: runs after CorrelationIdFilter (-300), before AuthPreFilter (-200).
 */
@Component
@ConditionalOnBean(FirebaseApp::class)
class AppCheckGatewayFilter(
    @Qualifier("appCheckCredentials") private val credentials: GoogleCredentials,
    @Qualifier("firebaseProjectId")   private val projectId: String
) : GlobalFilter, Ordered {

    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newHttpClient()
    private val objectMapper = ObjectMapper()

    private val exemptPatterns = listOf(
        "/actuator/",
        "/api/auth/",
        "/api/v1/guest/",
        "/fallback/",
        "/ws/",
        "/swagger",
        "/api-docs",
        "/webjars/",
        "/gateway-api-docs/"
    )

    override fun getOrder(): Int = -250

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val path = exchange.request.uri.path

        if (exemptPatterns.any { path.startsWith(it) }) {
            return chain.filter(exchange)
        }

        val appCheckToken = exchange.request.headers.getFirst("X-Firebase-AppCheck")
        if (appCheckToken.isNullOrBlank()) {
            log.warn("Missing X-Firebase-AppCheck header for path: {}", path)
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }

        return Mono.fromCallable {
            verifyToken(appCheckToken)
        }
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap { chain.filter(exchange) }
            .onErrorResume { ex ->
                log.warn("App Check token verification failed for path {}: {}", path, ex.message)
                exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                exchange.response.setComplete()
            }
    }

    private fun verifyToken(token: String) {
        if (projectId.isBlank()) {
            throw IllegalStateException("Firebase projectId not set — cannot verify App Check token")
        }
        credentials.refreshIfExpired()
        val accessToken = credentials.accessToken.tokenValue

        val body = objectMapper.writeValueAsString(mapOf("app_check_token" to token))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://firebaseappcheck.googleapis.com/v1/projects/$projectId:verifyAppCheckToken"))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("App Check verification rejected: HTTP ${response.statusCode()} — ${response.body()}")
        }
    }
}
