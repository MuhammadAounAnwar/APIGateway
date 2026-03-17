package com.ono.apigateway.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Configuration
class JwtConfig {

    @Bean
    fun jwtDecoder(@Value("\${security.jwt.public-key:}") publicKeyPem: String): ReactiveJwtDecoder {
        val cleaned = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = Base64.getDecoder().decode(cleaned)
        val spec = X509EncodedKeySpec(keyBytes)
        val rsaKey = KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey

        return NimbusReactiveJwtDecoder.withPublicKey(rsaKey).build()
    }
}
