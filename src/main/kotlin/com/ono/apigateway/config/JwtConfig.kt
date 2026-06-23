package com.ono.apigateway.config

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Configuration
class JwtConfig(
    @Value("\${security.jwt.public-key:}") private val publicKeyBase64: String,
    @Value("\${security.jwt.issuer:}") private val issuer: String,
    private val environment: Environment
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun validatePublicKey() {
        // In local-prod (testing), skip JWT validation if key not set
        if (environment.activeProfiles.contains("local-prod")) {
            if (publicKeyBase64.isBlank()) {
                logger.warn("⚠️  JWT public key not set in local-prod - JWT validation DISABLED for testing")
                return
            }
        }

        // In other profiles, require the public key
        require(publicKeyBase64.isNotBlank()) {
            "AUTH_SERVICE_PUBLIC_KEY environment variable is required but not set. " +
            "Set security.jwt.public-key in application.yaml or AUTH_SERVICE_PUBLIC_KEY env var."
        }
    }

    @Bean
    fun jwtDecoder(): ReactiveJwtDecoder? {
        // Skip JWT decoder creation in local-prod if key is not set
        if (environment.activeProfiles.contains("local-prod") && publicKeyBase64.isBlank()) {
            logger.info("🔓 JWT decoder disabled for local-prod testing (no public key provided)")
            return null
        }

        val rsaKey = parsePublicKey(publicKeyBase64)
        val decoder = NimbusReactiveJwtDecoder.withPublicKey(rsaKey).build()

        if (issuer.isNotBlank()) {
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer))
        }

        return decoder
    }

    private fun parsePublicKey(keyInput: String): RSAPublicKey {
        try {
            // First, try to decode as base64 (could be Base64(PEM) or Base64(DER))
            val decodedBytes = Base64.getDecoder().decode(keyInput.trim())
            val decodedStr = String(decodedBytes, Charsets.UTF_8)

            // Check if it's PEM format (has headers)
            if (decodedStr.contains("BEGIN PUBLIC KEY")) {
                // Extract the base64 content between headers
                val cleaned = decodedStr
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("\\s".toRegex(), "")

                val keyBytes = Base64.getDecoder().decode(cleaned)
                val keySpec = X509EncodedKeySpec(keyBytes)
                return KeyFactory.getInstance("RSA").generatePublic(keySpec) as RSAPublicKey
            } else {
                // Assume it's DER format
                val keySpec = X509EncodedKeySpec(decodedBytes)
                return KeyFactory.getInstance("RSA").generatePublic(keySpec) as RSAPublicKey
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse public key: ${e.message}", e)
        }
    }
}
