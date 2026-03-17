import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
    id("org.springframework.boot") version "3.2.5"
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

val springCloudVersion = "2023.0.4"

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

    // -------------------------------------------------------------------------
    // 6. Shared Observability Library
    // -------------------------------------------------------------------------
    implementation("com.github.MuhammadAounAnwar:logginglibrary-spring-boot-starter:1.0.6")

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
    // 9. Testing
    // -------------------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.projectreactor:reactor-test")
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
