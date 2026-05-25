package com.ono.apigateway.firebase

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream

@Configuration
class FirebaseAppCheckConfig {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    @Bean
    @ConditionalOnExpression("!'\${firebase.service-account-json:}'.empty")
    fun firebaseApp(
        @Value("\${firebase.service-account-json}") serviceAccountJson: String
    ): FirebaseApp {
        if (FirebaseApp.getApps().isNotEmpty()) {
            log.info("Firebase already initialized — reusing existing app")
            return FirebaseApp.getInstance()
        }
        val credentials = GoogleCredentials.fromStream(
            ByteArrayInputStream(serviceAccountJson.toByteArray())
        )
        val projectId = mapper.readTree(serviceAccountJson).path("project_id").asText(null)

        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .apply { projectId?.let { setProjectId(it) } }
            .build()
        log.info("Firebase App Check initialized for API Gateway (projectId={})", projectId ?: "unknown")
        return FirebaseApp.initializeApp(options)
    }

    /**
     * Scoped credentials used by AppCheckGatewayFilter to call the Firebase App Check REST API.
     * Exposed as a named bean to avoid Kotlin visibility issues with FirebaseOptions.getCredentials().
     */
    @Bean(name = ["appCheckCredentials"])
    @ConditionalOnExpression("!'\${firebase.service-account-json:}'.empty")
    fun appCheckCredentials(
        @Value("\${firebase.service-account-json}") serviceAccountJson: String
    ): GoogleCredentials =
        GoogleCredentials.fromStream(ByteArrayInputStream(serviceAccountJson.toByteArray()))
            .createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))

    /**
     * Firebase project ID parsed from the service account JSON.
     * Used by AppCheckGatewayFilter to build the App Check REST API URL.
     */
    @Bean(name = ["firebaseProjectId"])
    @ConditionalOnExpression("!'\${firebase.service-account-json:}'.empty")
    fun firebaseProjectId(
        @Value("\${firebase.service-account-json}") serviceAccountJson: String
    ): String =
        mapper.readTree(serviceAccountJson).path("project_id").asText("")
}
