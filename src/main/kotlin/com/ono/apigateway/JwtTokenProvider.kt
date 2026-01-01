package com.ono.apigateway

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
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
        return extractClaims(token).subject ?: throw JwtException("Invalid JWT token")
    }

    fun validateToken(token: String): Boolean {
        return try {
            extractClaims(token)
            true
        } catch (_: Exception) {
            false
        }
    }

}