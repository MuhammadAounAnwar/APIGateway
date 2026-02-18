package com.ono.apigateway.config

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ResourceAttributes
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.*

@Configuration
class OpenTelemetryConfig(

    @Value("\${spring.application.name:api-gateway}")
    private val applicationName: String,

    @Value("\${spring.profiles.active:default}")
    private val activeProfile: String,

    @Value("\${info.app.version:1.0.0}")
    private val serviceVersion: String
) {

    @Bean
    fun otelResource(): Resource {

        val instanceId = UUID.randomUUID().toString()

        return Resource.getDefault().merge(
            Resource.create(
                Attributes.builder()
                    // Core service identity
                    .put(ResourceAttributes.SERVICE_NAME, applicationName)
                    .put(ResourceAttributes.SERVICE_VERSION, serviceVersion)
                    .put(ResourceAttributes.SERVICE_INSTANCE_ID, instanceId)

                    // Logical grouping (optional but recommended)
                    .put(ResourceAttributes.SERVICE_NAMESPACE, "ono-platform")

                    // Environment (dev / prod / default)
                    .put(ResourceAttributes.DEPLOYMENT_ENVIRONMENT, activeProfile)

                    .build()
            )
        )
    }
}