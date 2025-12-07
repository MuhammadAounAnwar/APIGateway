plugins {
    // 1. **CRITICAL CHANGE:** Revert to stable Kotlin 1.9.x
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"

    id("org.springframework.boot") version "3.2.5" // Keep this stable version
    id("io.spring.dependency-management") version "1.1.7"
}

// ... rest of the build file

group = "com.ono"
version = "0.0.1-SNAPSHOT"
description = "Api Gateway"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
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

    // NOTE: 'spring-boot-starter-webflux' is generally included transitively by the Gateway starter.
    // Explicitly keeping it is harmless but unnecessary for modern Spring versions.

    // -------------------------------------------------------------------------
    // 2. Security & Filter Chains (Centralized Auth)
    // -------------------------------------------------------------------------
    // Reactive Security framework
    implementation("org.springframework.boot:spring-boot-starter-security")

    // For integrating with external OAuth2 providers (Login with Google/Facebook)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    // JWT processing - Required for custom JWT validation filters
    val jjwtVersion = "0.12.5"
    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

    // -------------------------------------------------------------------------
    // 3. Kotlin & Utilities
    // -------------------------------------------------------------------------
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Config/Environment Management - Fine to keep for loading .env files
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("me.paulschwarz:spring-dotenv:4.0.0")
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // -------------------------------------------------------------------------
    // 4. Monitoring & Metrics
    // -------------------------------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // -------------------------------------------------------------------------
    // 5. Testing
    // -------------------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // ESSENTIAL for testing reactive (WebFlux/Gateway) components
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}