import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
    id("org.springframework.boot") version "3.3.5"
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

val springCloudVersion = "2023.0.1"

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
    }
}

dependencies {
    // -------------------------------------------------------------------------
    // 1. Core Reactive Gateway & Discovery
    // -------------------------------------------------------------------------
    implementation("org.springframework.cloud:spring-cloud-starter-gateway")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")

    // -------------------------------------------------------------------------
    // 2. Security & Filter Chains (Centralized Auth)
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // 5. Resilience4j for Circuit Breaker (Spring Cloud starter manages versions)
    // -------------------------------------------------------------------------
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")
    implementation("io.github.resilience4j:resilience4j-micrometer")
    implementation("io.github.resilience4j:resilience4j-timelimiter")

    // -------------------------------------------------------------------------
    // 6. Shared Observability Library
    // -------------------------------------------------------------------------
    implementation("com.github.MuhammadAounAnwar:logginglibrary:1.0.8")

    // Logbook — HTTP request/response logging (logginglibrary uses compileOnly, must add explicitly)
    implementation("org.zalando:logbook-spring-boot-starter:3.9.0")

    // -------------------------------------------------------------------------
    // 7. Redis (Reactive — matches WebFlux stack)
    // -------------------------------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    // -------------------------------------------------------------------------
    // 8. Tracing (OpenTelemetry — single bridge, NOT brave)
    // -------------------------------------------------------------------------
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // -------------------------------------------------------------------------
    // 8b. API Documentation (SpringDoc OpenAPI — WebFlux variant)
    // -------------------------------------------------------------------------
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.5.0")

    // -------------------------------------------------------------------------
    // 9. Firebase Admin SDK (App Check verification)
    // -------------------------------------------------------------------------
    implementation("com.google.firebase:firebase-admin:9.3.0") {
        // Exclude the conflicting gRPC and use netty explicitly
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
    }
    // Explicitly add gRPC netty (non-shaded) with correct version
    implementation("io.grpc:grpc-netty:1.68.0")

    // -------------------------------------------------------------------------
    // 9b. Netty Native DNS Resolver for macOS (fixes DNS resolution on Apple Silicon)
    // -------------------------------------------------------------------------
    runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.1.108.Final:osx-aarch_64")

    // -------------------------------------------------------------------------
    // 10. Testing
    // -------------------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/* -------------------------------------------------------------------------
 * Kotlin Compiler Configuration
 * ------------------------------------------------------------------------- */
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
