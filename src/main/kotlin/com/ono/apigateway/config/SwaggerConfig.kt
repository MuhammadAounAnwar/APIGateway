package com.ono.apigateway.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("SipSync / ONO Platform — API Gateway")
                .version("1.0.0")
                .description(
                    """
                    Single entry point for all platform APIs.

                    **Authentication:** Send a Bearer JWT (issued by Auth Service) in the `Authorization` header for all secured endpoints.

                    **Rate Limiting:** Responses include `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` headers.

                    **Downstream services** are available as separate tabs in Swagger UI.
                    Select a tab from the dropdown to browse a specific service's full API contract.
                    """.trimIndent()
                )
                .contact(Contact().name("ONO Platform Team"))
        )
        .addServersItem(Server().url("http://localhost:8762").description("Local development"))
        .components(
            Components().addSecuritySchemes(
                "BearerAuth",
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT access token issued by the Auth Service (/api/auth/login)")
            )
        )
}
