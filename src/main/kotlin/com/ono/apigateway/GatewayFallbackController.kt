package com.ono.apigateway

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/fallback")
class GatewayFallbackController {

    @GetMapping("/auth")
    fun authServiceFallback(): ResponseEntity<Map<String, Any>> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(
                mapOf(
                    "code" to "SERVICE_UNAVAILABLE",
                    "message" to "User service is temporarily unavailable",
                    "service" to "USER-SERVICE"
                )
            )

    @GetMapping("/user")
    fun userServiceFallback(): ResponseEntity<Map<String, Any>> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(
                mapOf(
                    "code" to "SERVICE_UNAVAILABLE",
                    "message" to "User service is temporarily unavailable",
                    "service" to "USER-SERVICE"
                )
            )

    @GetMapping("/restaurant")
    fun restaurantServiceFallback(): ResponseEntity<Map<String, Any>> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(
                mapOf(
                    "code" to "SERVICE_UNAVAILABLE",
                    "message" to "User service is temporarily unavailable",
                    "service" to "USER-SERVICE"
                )
            )

    @GetMapping("/order")
    fun orderServiceFallback(): ResponseEntity<Map<String, Any>> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(
                mapOf(
                    "code" to "SERVICE_UNAVAILABLE",
                    "message" to "Order service is currently down",
                    "service" to "ORDER-SERVICE"
                )
            )
}
