package com.ono.apigateway

import com.ono.logginglibrary.exception.InvalidTokenException
import com.ono.logginglibrary.exception.TokenExpiredException
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
    private val key: SecretKey = Keys.hmacShaKeyFor(jwtSecret.toByteArray())

    init {
        println("JWT Secret loaded successfully: $jwtSecret") // Check the loaded value
    }

    fun extractClaims(token: String): Claims {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
    }

    fun extractEmail(token: String): String {
        return extractClaims(token).subject ?: throw InvalidTokenException("Invalid JWT token")
    }

    fun extractJti(token: String): String {
        return extractClaims(token).id ?: throw InvalidTokenException("Invalid JWT token")
    }

    fun validateToken(token: String): Boolean {
        return try {
            extractClaims(token)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun extractExpiration(token: String) =
        extractClaims(token).expiration ?: throw TokenExpiredException("Expired JWT token")

    fun getRemainingValiditySeconds(token: String): Long {

        extractClaims(token).expiration
        val expiration = extractExpiration(token)
        return Duration.between(Instant.now(), expiration.toInstant()).seconds
    }

}