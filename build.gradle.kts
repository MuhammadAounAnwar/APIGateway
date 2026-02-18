import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // 1. **CRITICAL CHANGE:** Revert to stable Kotlin 1.9.x
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"

    id("org.springframework.boot") version "3.2.5" // Keep this stable version
    id("io.spring.dependency-management") version "1.1.7"
}


group = "com.ono"
version = "0.0.1-SNAPSHOT"
description = "Api Gateway"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Ensure this Spring Cloud version is compatible with the Spring Boot version above (3.3.1 -> 2023.0.4 is compatible)
val springCloudVersion = "2023.0.4"

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
    }
}

dependencies {
    // -------------------------------------------------------------------------
    // 1. Core Reactive Gateway & Discovery (ESSENTIAL)
    // -------------------------------------------------------------------------
    // Core Gateway functionality (WebFlux based)
    implementation("org.springframework.cloud:spring-cloud-starter-gateway")

    // Service Discovery (for lb:// routing)
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")

    // NOTE: 'spring-boot-starter-webflux' is generally included transitively by the Gateway starter.
    // Explicitly keeping it is harmless but unnecessary for modern Spring versions.

    // -------------------------------------------------------------------------
    // 2. Security & Filter Chains (Centralized Auth)
    // -------------------------------------------------------------------------
    // Reactive Security framework
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // -------------------------------------------------------------------------
    // 3. Kotlin & Utilities
    // -------------------------------------------------------------------------
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // -------------------------------------------------------------------------
    // 4. Monitoring & Metrics
    // -------------------------------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")

    // -------------------------------------------------------------------------
    // 5. Testing
    // -------------------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    // -------------------------------------------------------------------------
    // 6. Resilience4j for Circuit Breaker
    // -------------------------------------------------------------------------
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")
    implementation("io.github.resilience4j:resilience4j-micrometer")
    implementation("io.github.resilience4j:resilience4j-bulkhead") // Added for Bulkhead support

    // -------------------------------------------------------------------------
    // 7. Logging Library
    // -------------------------------------------------------------------------
    implementation("com.github.MuhammadAounAnwar:logginglibrary:1.0.4")

    // -------------------------------------------------------------------------
    // 8. Redis Library
    // -------------------------------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    // -------------------------------------------------------------------------
    // 9. Tracing Library
    // -------------------------------------------------------------------------
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // ESSENTIAL for testing reactive (WebFlux/Gateway) components
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/* -------------------------------------------------------------------------
 * Kotlin Compiler Configuration (SINGLE place)
 * ------------------------------------------------------------------------- */
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property"
        )
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}