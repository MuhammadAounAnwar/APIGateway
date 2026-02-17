package com.ono.apigateway

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @param:Value("\${spring.security.jwt.secret}")
    private val jwtSecret: String
) {

    /**
     * NOTE:
     * In a production-grade API Gateway using Spring Security OAuth2 Resource Server,
     * this class is NOT required.
     *
     * Gateway should delegate JWT validation to Spring Security instead of manually parsing.
     *
     * This implementation is kept only if you explicitly need manual parsing.
     */

    private val key: SecretKey = Keys.hmacShaKeyFor(jwtSecret.toByteArray())

    private fun resolveToken(token: String): String {
        return if (token.startsWith("Bearer ")) {
            token.substring(7)
        } else {
            token
        }
    }

    fun extractClaims(token: String): Claims {
        val cleanedToken = resolveToken(token)

        return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(cleanedToken)
            .payload
    }

    fun extractEmail(token: String): String {
        return extractClaims(token).subject
            ?: throw IllegalArgumentException("JWT subject (email) is missing")
    }

    fun extractJti(token: String): String {
        return extractClaims(token).id
            ?: throw IllegalArgumentException("JWT ID (jti) is missing")
    }

    fun extractExpiration(token: String): Instant {
        return extractClaims(token).expiration?.toInstant()
            ?: throw IllegalStateException("JWT expiration is missing")
    }

    fun isExpired(token: String): Boolean {
        return try {
            extractExpiration(token).isBefore(Instant.now())
        } catch (ex: Exception) {
            true
        }
    }

    fun validateToken(token: String): Boolean {
        return try {
            extractClaims(token)
            !isExpired(token)
        } catch (ex: Exception) {
            false
        }
    }

    fun getRemainingValiditySeconds(token: String): Long {
        val expiration = extractExpiration(token)
        return Duration.between(Instant.now(), expiration).seconds.coerceAtLeast(0)
    }
}