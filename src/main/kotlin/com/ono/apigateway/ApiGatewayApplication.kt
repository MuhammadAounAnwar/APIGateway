package com.ono.apigateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.client.discovery.EnableDiscoveryClient

/**
 * API Gateway Application
 *
 * Responsibilities:
 * - Service discovery integration (Eureka)
 * - Centralized routing
 * - Security enforcement (JWT validation)
 * - Global filters (logging, correlation-id, etc.)
 *
 * NOTE:
 * This class must remain infrastructure-only.
 * No business logic, repositories, or domain code should be added here.
 */
@SpringBootApplication(
    scanBasePackages = ["com.ono"]
)
@EnableDiscoveryClient
class ApiGatewayApplication

fun main(args: Array<String>) {
    runApplication<ApiGatewayApplication>(*args)
}
