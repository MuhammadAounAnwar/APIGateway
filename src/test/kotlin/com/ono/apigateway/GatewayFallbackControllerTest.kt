package com.ono.apigateway

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayFallbackControllerTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Test
    fun `known service fallback returns 503 with structured body`() {
        webTestClient.get().uri("/fallback/auth-service")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.status").isEqualTo(503)
            .jsonPath("$.service").isEqualTo("AUTH-SERVICE")
            .jsonPath("$.message").isNotEmpty
            .jsonPath("$.timestamp").isNotEmpty
    }

    @Test
    fun `all known services return 503`() {
        listOf("auth-service", "user-service", "order-service", "restaurant-service",
            "notification-service", "email-service", "chat-service", "spozon-backend").forEach { service ->
            webTestClient.get().uri("/fallback/$service")
                .exchange()
                .expectStatus().isEqualTo(503)
        }
    }

    @Test
    fun `unknown service fallback returns 404`() {
        webTestClient.get().uri("/fallback/non-existent-service")
            .exchange()
            .expectStatus().isNotFound()
    }
}
