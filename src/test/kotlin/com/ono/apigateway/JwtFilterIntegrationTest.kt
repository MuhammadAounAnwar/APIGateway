package com.ono.apigateway

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class JwtFilterIntegrationTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @MockBean
    lateinit var jwtDecoder: ReactiveJwtDecoder

    @BeforeEach
    fun setUp() {
        // By default every token decode is rejected — specific tests override this for valid tokens.
        whenever(jwtDecoder.decode(any())).thenReturn(
            Mono.error(BadJwtException("Invalid or malformed token"))
        )
    }

    @Test
    fun `request to secured path without token returns 401`() {
        webTestClient.get().uri("/api/users/me")
            .exchange()
            .expectStatus().isUnauthorized()
    }

    @Test
    fun `request to secured path with malformed token returns 401`() {
        webTestClient.get().uri("/api/users/me")
            .header("Authorization", "Bearer not.a.valid.jwt")
            .exchange()
            .expectStatus().isUnauthorized()
    }

    @Test
    fun `request to public auth path without token is not rejected by security`() {
        // /api/auth/** is permitAll — the security filter lets it through.
        // With empty routes in test config, gateway returns 404 (no route), not 401.
        webTestClient.get().uri("/api/auth/login")
            .exchange()
            .expectStatus().isNotFound()
    }

    @Test
    fun `request with valid JWT passes security filter`() {
        val jwt = Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .subject("user-42")
            .claim("roles", listOf("ROLE_USER"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build()

        whenever(jwtDecoder.decode(any())).thenReturn(Mono.just(jwt))

        // Security passes — gateway returns 404 (no matching route in test config), not 401.
        webTestClient.get().uri("/api/users/me")
            .header("Authorization", "Bearer test-token")
            .exchange()
            .expectStatus().isNotFound()
    }

    @Test
    fun `request with expired JWT is rejected by security filter`() {
        whenever(jwtDecoder.decode(any())).thenReturn(
            Mono.error(org.springframework.security.oauth2.core.OAuth2AuthenticationException("token_expired"))
        )

        webTestClient.get().uri("/api/users/profile")
            .header("Authorization", "Bearer expired-token")
            .exchange()
            .expectStatus().isUnauthorized()
    }

    @Test
    fun `fallback endpoint is accessible without token`() {
        webTestClient.get().uri("/fallback/auth-service")
            .exchange()
            .expectStatus().isEqualTo(503)
    }
}
