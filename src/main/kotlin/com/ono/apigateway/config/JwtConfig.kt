package com.ono.apigateway.config

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Configuration
class JwtConfig(
    @Value("\${security.jwt.public-key:}") private val publicKeyBase64: String,
    @Value("\${security.jwt.issuer:}") private val issuer: String
) {

    @PostConstruct
    fun validatePublicKey() {
        require(publicKeyBase64.isNotBlank()) {
            "AUTH_SERVICE_PUBLIC_KEY environment variable is required but not set. " +
            "Set security.jwt.public-key in application.yaml or AUTH_SERVICE_PUBLIC_KEY env var."
        }
    }

    @Bean
    fun jwtDecoder(): ReactiveJwtDecoder {
        val cleaned = publicKeyBase64
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = Base64.getDecoder().decode(cleaned)
        val spec = X509EncodedKeySpec(keyBytes)
        val rsaKey = KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey

        val decoder = NimbusReactiveJwtDecoder.withPublicKey(rsaKey).build()

        if (issuer.isNotBlank()) {
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer))
        }

        return decoder
    }
}
