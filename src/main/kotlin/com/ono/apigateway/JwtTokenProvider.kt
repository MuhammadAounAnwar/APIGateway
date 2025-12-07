package com.ono.apigateway

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.Key

@Component
class JwtTokenProvider(
    @param:Value("\${security.jwt.secret}")
    private val jwtSecret: String
) {
    private val key: Key = Keys.hmacShaKeyFor(jwtSecret.toByteArray())

    fun extractClaims(token: String): Claims {
        return Jwts.parser().setSigningKey(key).build().parseClaimsJws(token).payload
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