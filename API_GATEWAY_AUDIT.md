# API Gateway — Complete Code Audit

**Date:** 2026-05-13  
**Service:** `apigateway`  
**Stack:** Spring Boot 3.2.5 · Kotlin 1.9.24 · Spring Cloud Gateway (WebFlux) · Redis · Resilience4j · OpenTelemetry  
**Port:** 8762  
**Codebase:** ~1,025 lines across 13 source files

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Project Structure](#2-project-structure)
3. [Route Mapping](#3-route-mapping)
4. [Middleware & Filter Flow](#4-middleware--filter-flow)
5. [Authentication & Authorization](#5-authentication--authorization)
6. [Rate Limiting & Throttling](#6-rate-limiting--throttling)
7. [Request/Response Transformation](#7-requestresponse-transformation)
8. [Error Handling & Fallbacks](#8-error-handling--fallbacks)
9. [Service Discovery & Load Balancing](#9-service-discovery--load-balancing)
10. [Logging & Monitoring](#10-logging--monitoring)
11. [Architecture & Design Decisions](#11-architecture--design-decisions)
12. [Issues Found](#12-issues-found)
13. [Improvements Needed](#13-improvements-needed)

---

## 1. Project Overview

The `apigateway` is the single entry point for all external traffic in the SipSync/ONO platform. Its responsibilities are:

- **Routing** — forward requests to 7 downstream microservices via Eureka load-balanced URIs
- **Authentication** — validate JWTs issued by `authclient` at the gateway edge; downstream services receive trusted `X-User-*` headers instead of raw tokens
- **Rate limiting** — per-user and per-IP fixed-window counters in Redis
- **Resilience** — per-route circuit breaker, retry, bulkhead, and time limiter via Resilience4j
- **Observability** — distributed tracing via OpenTelemetry (W3C propagation), Prometheus metrics, and structured log correlation
- **Token lifecycle** — Redis-backed JWT blacklist (logout support) and JTI cache

The gateway does **not** contain business logic. `ApiGatewayApplication.kt` explicitly documents this constraint (`scanBasePackages = ["com.ono.apigateway"]`, comment: "No business logic, repositories, or domain code").

---

## 2. Project Structure

```
apigateway/
├── src/main/kotlin/com/ono/apigateway/
│   ├── ApiGatewayApplication.kt            # Entry point; @EnableDiscoveryClient
│   ├── config/
│   │   ├── JwtConfig.kt                    # NimbusReactiveJwtDecoder (RSA public key)
│   │   ├── OpenTelemetryConfig.kt          # OTel Resource attributes (service.name, env, instance.id)
│   │   ├── ResilienceTracingConfig.kt      # Resilience4j → OTel span bridges
│   │   └── SecurityConfig.kt              # SecurityWebFilterChain; JWT converter; route authorization
│   ├── filter/
│   │   ├── AuthPreFilterGatewayFilterFactory.kt  # Custom per-route filter: auth + rate limit + header injection
│   │   └── ErrorHandlingFilter.kt         # @Order(-1) global filter; exception → JSON error response
│   ├── redis/
│   │   ├── GatewayRateLimiter.kt           # Fixed-window counter in Redis
│   │   ├── RedisKeys.kt                    # Centralized Redis key factory (gateway:* namespace)
│   │   ├── RedisLuaConfig.kt               # Lua script bean — UNUSED (dead code)
│   │   ├── RedisRateLimitService.kt        # Lua-based rate limiter — UNUSED (dead code)
│   │   └── TokenStoreService.kt            # JWT JTI cache + blacklist in Redis
│   ├── resillience/                        # ← typo: should be "resilience"
│   │   └── GatewayFallbackController.kt   # /fallback/{service} → 503 response
│   ├── security/
│   │   └── RouteValidator.kt              # Classifies URI as public vs secured
│   └── util/
│       └── Extensions.kt                  # AuthContext, Tracer/Exchange extension functions
├── src/main/resources/
│   ├── application.yaml                   # Base config: port 8762, routes, CORS, observability
│   ├── application-dev.yaml               # Dev: H2 Redis, relaxed resilience, 100% trace sampling
│   └── application-prod.yaml              # Prod: env-var driven, strict resilience, 20% sampling
├── src/test/resources/
│   └── application.yaml                   # Test overrides
└── build.gradle.kts                       # Spring Cloud BOM 2023.0.4
```

**Package layout:** Flat by concern (`config`, `filter`, `redis`, `security`, `util`) with a single structural sub-module for resilience fallback. All infrastructure — no domain objects.

---

## 3. Route Mapping

All routes are defined in `application.yaml:37–230`. Every route uses Eureka load-balanced URIs (`lb://`).

### Global Default Filters (applied to every route)

| Filter | Effect |
|--------|--------|
| `AddRequestHeader=X-Gateway, API-GATEWAY` | Marks upstream requests as gateway-originated |
| `AddResponseHeader=X-Gateway-Version, v1` | Injects version tag in every response |
| `DedupeResponseHeader=Access-Control-Allow-Credentials Access-Control-Allow-Origin` | Prevents duplicate CORS headers |
| `RemoveResponseHeader=Server` | Strips backend server identity from responses |

---

### Route 1 — Auth Service (PUBLIC)

| Property | Value |
|----------|-------|
| **ID** | `auth_service` |
| **Predicate** | `Path=/api/auth/**` |
| **Upstream** | `lb://auth-client` |
| **Path rewrite** | None — forwarded as-is |
| **Auth** | **None** (public) |

**Filters (in order):**
1. `RequestRateLimiter` — replenish `${AUTH_RATE_LIMIT:10}` req/s, burst `${AUTH_BURST_CAPACITY:20}`
2. `Retry(name=default)`
3. `CircuitBreaker(name=default, fallback=/fallback/auth-service)`

---

### Route 2 — User Service (SECURED)

| Property | Value |
|----------|-------|
| **ID** | `user_service` |
| **Predicate** | `Path=/api/users/**` |
| **Upstream** | `lb://USER-SERVICE` |
| **Path rewrite** | `/api/users/(?<segment>.*) → /${segment}` |
| **Auth** | JWT required (`AuthPreFilter`) |

**Filters (in order):**
1. `RewritePath` — strips `/api/users` prefix
2. `AuthPreFilter` — JWT validation + rate limit + header injection
3. `RequestRateLimiter` — replenish `${USER_RATE_LIMIT:20}`, burst `${USER_BURST_CAPACITY:40}`
4. `Retry(name=default)`
5. `TimeLimiter(name=default)` — 6s dev / 4s prod
6. `Bulkhead(name=default)` — 50 dev / 30 prod concurrent
7. `CircuitBreaker(name=default, fallback=/fallback/user-service)`

---

### Route 3 — Restaurant Service (SECURED)

| Property | Value |
|----------|-------|
| **ID** | `restaurant_service` |
| **Predicate** | `Path=/api/restaurants/**` |
| **Upstream** | `lb://RESTAURANT-SERVICE` |
| **Path rewrite** | `/api/restaurants/(?<segment>.*) → /${segment}` |
| **Auth** | JWT required |

**Filters:** Same structure as User Service. `RequestRateLimiter`: replenish `${RESTAURANT_RATE_LIMIT:30}`, burst `${RESTAURANT_BURST_CAPACITY:60}`.

---

### Route 4 — Order Service (SECURED — Strict)

| Property | Value |
|----------|-------|
| **ID** | `order_service` |
| **Predicate** | `Path=/api/orders/**` |
| **Upstream** | `lb://ORDER-SERVICE` |
| **Path rewrite** | `/api/orders/(?<segment>.*) → /${segment}` |
| **Auth** | JWT required |
| **Profile** | Uses `orderStrict` resilience config |

**`orderStrict` resilience settings:**

| Setting | Dev | Prod |
|---------|-----|------|
| Circuit breaker failure threshold | 50% | 30% |
| Circuit breaker wait (open) | 15s | 45s |
| Max retries | 2 | 2 |
| Retry wait | 400ms | 250ms |
| Bulkhead concurrent | 30 | 20 |
| TimeLimiter timeout | 4s | 4s |

**RequestRateLimiter:** replenish `${ORDER_RATE_LIMIT:15}`, burst `${ORDER_BURST_CAPACITY:30}` (lowest of any route).

---

### Route 5 — Notification Service (SECURED)

| Property | Value |
|----------|-------|
| **ID** | `notification_service` |
| **Predicate** | `Path=/api/notifications/**` |
| **Upstream** | `lb://notificationservice` |
| **Path rewrite** | None |
| **Auth** | JWT required |

**RequestRateLimiter:** replenish `${NOTIFICATION_RATE_LIMIT:25}`, burst `${NOTIFICATION_BURST_CAPACITY:50}`.

---

### Route 6 — Email Service (SECURED — No Rate Limit)

| Property | Value |
|----------|-------|
| **ID** | `email_service` |
| **Predicate** | `Path=/api/mails/**` |
| **Upstream** | `lb://emailservice` |
| **Path rewrite** | None |
| **Auth** | JWT required |
| **Profile** | Uses `emailLenient` resilience config |

**⚠ Missing:** `RequestRateLimiter` filter is absent. Email sending is unbounded.

**`emailLenient` resilience settings:**

| Setting | Dev | Prod |
|---------|-----|------|
| Circuit breaker failure threshold | 80% | 70% |
| Circuit breaker wait (open) | 5s | 15s |
| Max retries | 4 | 2 (inherits default) |
| Bulkhead concurrent | 20 | 10 |
| TimeLimiter timeout | 8s | 4s |

---

### Route 7 — Chat Service REST (SECURED)

| Property | Value |
|----------|-------|
| **ID** | `chat_service` |
| **Predicate** | `Path=/api/v1/chat/**` |
| **Upstream** | `lb://CHAT-SERVICE` |
| **Path rewrite** | None |
| **Auth** | JWT required |

**RequestRateLimiter:** replenish `${CHAT_RATE_LIMIT:50}`, burst `${CHAT_BURST_CAPACITY:100}` (highest of any route).

---

### Route 8 — Chat Service WebSocket (PUBLIC — No Filters)

| Property | Value |
|----------|-------|
| **ID** | `chat_service_ws` |
| **Predicate** | `Path=/ws/chat/**` |
| **Upstream** | `lb://CHAT-SERVICE` |
| **Auth** | **Deferred to handler** (comment at line 225) |
| **Filters** | **None** |

**⚠ Risk:** No filters applied. No rate limiting. JWT validation is an informal contract with the downstream handler.

---

### Route Summary Table

| Route ID | Path | Upstream | Auth | Rate Limit | Resilience Profile |
|---------|------|---------|------|-----------|-------------------|
| `auth_service` | `/api/auth/**` | `auth-client` | None | 10/s | default |
| `user_service` | `/api/users/**` | `USER-SERVICE` | JWT | 20/s | default |
| `restaurant_service` | `/api/restaurants/**` | `RESTAURANT-SERVICE` | JWT | 30/s | default |
| `order_service` | `/api/orders/**` | `ORDER-SERVICE` | JWT | 15/s | orderStrict |
| `notification_service` | `/api/notifications/**` | `notificationservice` | JWT | 25/s | default |
| `email_service` | `/api/mails/**` | `emailservice` | JWT | **None** | emailLenient |
| `chat_service` | `/api/v1/chat/**` | `CHAT-SERVICE` | JWT | 50/s | default |
| `chat_service_ws` | `/ws/chat/**` | `CHAT-SERVICE` | **None** | **None** | None |

---

## 4. Middleware & Filter Flow

### Execution Order

```
Inbound Request
      │
      ▼
① ErrorHandlingFilter        @Order(-1)  GlobalFilter
  └─ Wraps entire chain; catches all exceptions; writes JSON error response
      │
      ▼
② Spring Security WebFilter  (implicit order, pre-routing)
  └─ Validates JWT via NimbusReactiveJwtDecoder
  └─ Builds Authentication + ReactiveSecurityContextHolder
  └─ Blocks request if not authenticated (for secured paths)
      │
      ▼
③ Spring Cloud Gateway RoutePredicateHandlerMapping
  └─ Matches request path to route definition
  └─ Applies per-route filters in declared order
      │
      ▼
④ Global Default Filters (AddRequestHeader, AddResponseHeader, etc.)
      │
      ▼
⑤ Per-Route Filters (for secured routes):
   a. RewritePath           — strip /api/{service} prefix (on 3 routes)
   b. AuthPreFilter         — extract auth context, check blacklist, rate limit, inject headers
   c. RequestRateLimiter    — Spring Cloud Gateway built-in Redis rate limiter
   d. Retry                 — retry on failure (profile-configurable)
   e. TimeLimiter           — timeout enforcement
   f. Bulkhead              — max concurrent call limit
   g. CircuitBreaker        — failure threshold tripping, fallback on open
      │
      ▼
⑥ Netty HTTP Client → Upstream Service
      │
      ▼
Outbound Response (reverse through filter chain)
```

### Key Filter Details

#### `ErrorHandlingFilter` (`filter/ErrorHandlingFilter.kt`)

- **Order:** `-1` (outermost — must wrap everything else)
- **Exception mapping:**
  - `ResponseStatusException` → extracts embedded `statusCode`
  - `TimeoutException` → `504 Gateway Timeout`
  - All others → `500 Internal Server Error`
- **Safe message policy:** Only `ResponseStatusException.reason` is surfaced; all other exceptions return `"Unexpected internal error"` (no stack traces or internal details in responses)
- **Response guard (line 59):** Checks `response.isCommitted` before writing — prevents double-write errors when the upstream already started sending
- **Tracing (lines 45–47):** Tags current span with `error=true`, `error.type`, and calls `span.error(ex)`

#### `AuthPreFilterGatewayFilterFactory` (`filter/AuthPreFilterGatewayFilterFactory.kt`)

**Public route path (line 47–60):**
1. Build Redis key: `gateway:rate:ip:{ip}`
2. Check fixed-window counter (50 req / 60s, hardcoded)
3. Inject `X-RateLimit-*` headers
4. Continue chain

**Secured route path (line 62–112):**
1. `exchange.requireAuthContext()` — extracts `Jwt` + roles from `ReactiveSecurityContextHolder`
2. Validate `jwt.subject` (userId) and `jwt.id` (jti) are non-null → `401` if either missing
3. Emit OTel trace event `authentication.success`
4. Check user rate limit: `gateway:rate:user:{userId}` (200 req / 60s, hardcoded)
5. Check Redis blacklist: `tokenStoreService.isBlacklisted(jti)` — fail-safe (Redis failure → reject)
6. Cache JTI: `tokenStoreService.cacheIfAbsent(jti, userId, ttl)` if TTL > 0 — fail-open
7. Extract `tenantId` claim (default `"default"`)
8. Inject `X-User-Id`, `X-User-Roles`, `X-Tenant-ID` headers via `exchange.mutate()`
9. Continue chain

---

## 5. Authentication & Authorization

### JWT Validation (`config/JwtConfig.kt` + `config/SecurityConfig.kt`)

**Decoder:** `NimbusReactiveJwtDecoder.withPublicKey(rsaPublicKey)` — RSA signature validation.  
**Key source:** `${AUTH_SERVICE_PUBLIC_KEY:}` — Base64-encoded DER/PEM public key. **Default is empty string** (service fails at first validation attempt if env var missing — not a startup failure, a runtime failure).

**Validations performed automatically by Nimbus:**
- RSA signature ✓
- `exp` claim (token expiry) ✓

**Validations NOT performed:**
- `iss` (issuer) — any issuer with a matching key is accepted
- `aud` (audience) — tokens intended for other services are accepted
- `nbf` (not before) — not configured

**JWT Roles Converter (`SecurityConfig.kt:68–78`):**  
Reads `"roles"` claim as `List<String>`. Each role is wrapped in `ROLE_` prefix if missing, then converted to `SimpleGrantedAuthority`. Roles are also available as comma-separated string in `X-User-Roles` header.

### Route Authorization Matrix

| Path Pattern | Security Level | Mechanism |
|-------------|---------------|-----------|
| `/api/auth/**` | Public | `permitAll()` in SecurityConfig |
| `/ws/**` | Public | `permitAll()` — JWT deferred to handler |
| `/actuator/health` | Public | `permitAll()` |
| `/actuator/**` | Admin | `hasRole("ADMIN")` |
| `OPTIONS *` | Public | `permitAll()` — CORS preflight |
| Everything else | Authenticated | `authenticated()` → Spring Security validates JWT |

### `RouteValidator` (`security/RouteValidator.kt`)

Used by `AuthPreFilter` to decide between IP-rate-limit (public) vs user-auth flow (secured):

```kotlin
private val openApiEndpoints = listOf(
    "/auth/login", "/auth/register", "/auth/forgot-password",
    "/auth/reset-password", "/auth/refresh", "/ws/"
)
fun isSecured(uri: String) = openApiEndpoints.none { uri.contains(it) }
```

**⚠ Uses string containment** — `/auth/login` would match `/api/custom/auth/login-service`. See Issues §12.

---

## 6. Rate Limiting & Throttling

### Two Parallel Rate Limiting Systems

#### System A — Spring Cloud Gateway `RequestRateLimiter` (YAML-configured, per-route)

Applied via `name: RequestRateLimiter` filter in routes. Uses Spring Cloud Gateway's built-in Redis token bucket. Configured with `redis-rate-limiter.*` properties per route:

| Route | Replenish Rate | Burst Capacity |
|-------|--------------|----------------|
| Auth | `${AUTH_RATE_LIMIT:10}` | `${AUTH_BURST_CAPACITY:20}` |
| User | `${USER_RATE_LIMIT:20}` | `${USER_BURST_CAPACITY:40}` |
| Restaurant | `${RESTAURANT_RATE_LIMIT:30}` | `${RESTAURANT_BURST_CAPACITY:60}` |
| Order | `${ORDER_RATE_LIMIT:15}` | `${ORDER_BURST_CAPACITY:30}` |
| Notification | `${NOTIFICATION_RATE_LIMIT:25}` | `${NOTIFICATION_BURST_CAPACITY:50}` |
| Email | **Missing** | **Missing** |
| Chat REST | `${CHAT_RATE_LIMIT:50}` | `${CHAT_BURST_CAPACITY:100}` |
| Chat WS | N/A | N/A |

#### System B — Custom `GatewayRateLimiter` in `AuthPreFilter` (code-level, per user/IP)

**Algorithm:** Fixed-window counter in Redis (INCR + EXPIRE on first call per window).

| Dimension | Key Pattern | Limit | Window |
|----------|------------|-------|--------|
| Public routes (IP) | `gateway:rate:ip:{ip}` | 50 req | 60s |
| Secured routes (user) | `gateway:rate:user:{userId}` | 200 req | 60s |

**Fail-open:** Redis failure logs an error but allows the request through.

**⚠ Hardcoded:** Both limits (50 and 200) are inline constants in `AuthPreFilterGatewayFilterFactory.kt:50,78` — not configurable via environment variables.

### Dead Code — `RedisRateLimitService` + `RedisLuaConfig`

`redis/RedisRateLimitService.kt` and `redis/RedisLuaConfig.kt` define a Lua-script-based rate limiter that is never injected or called. These are vestigial from an earlier implementation iteration.

---

## 7. Request/Response Transformation

### Headers Added to Every Upstream Request (global)

| Header | Value | Added By |
|--------|-------|---------|
| `X-Gateway` | `API-GATEWAY` | `AddRequestHeader` (global default) |

### Headers Added by `AuthPreFilter` (secured routes only)

| Header | Value | Source |
|--------|-------|--------|
| `X-User-Id` | JWT `sub` claim (userId) | `AuthPreFilter:126` |
| `X-User-Roles` | Comma-separated authority list | `AuthPreFilter:127` |
| `X-Tenant-ID` | JWT `tenantId` claim (or `"default"`) | `AuthPreFilter:128` |
| `X-RateLimit-Limit` | Total quota | `AuthPreFilter:151` |
| `X-RateLimit-Remaining` | Remaining in window | `AuthPreFilter:152` |
| `X-RateLimit-Reset` | Unix epoch seconds of window reset | `AuthPreFilter:153` |

### Headers Added to Every Response (global)

| Header | Value | Added By |
|--------|-------|---------|
| `X-Gateway-Version` | `v1` | `AddResponseHeader` (global default) |

### Headers Removed from Every Response

| Header | Reason |
|--------|--------|
| `Server` | Security: don't expose backend identity |

### Path Rewriting (3 routes)

| Route | Inbound | Forwarded |
|-------|---------|---------|
| `user_service` | `/api/users/me` | `/me` |
| `restaurant_service` | `/api/restaurants/123` | `/123` |
| `order_service` | `/api/orders/456/confirm` | `/456/confirm` |

Routes without rewriting forward the full path as-is (auth, notifications, email, chat).

---

## 8. Error Handling & Fallbacks

### Global Error Handler (`filter/ErrorHandlingFilter.kt`)

Single `@Order(-1)` `GlobalFilter` wraps the entire filter chain in `onErrorResume`. This is the catch-all for all unhandled exceptions — routing failures, timeout exceptions, upstream errors.

**Error response body:**
```json
{
  "timestamp": "2026-05-13T14:30:00.000Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "Unexpected internal error",
  "path": "/api/users/me",
  "traceId": "abc123"
}
```

All error responses include `traceId` for log correlation.

### Circuit Breaker Fallbacks (`resillience/GatewayFallbackController.kt`)

Each circuit-breaker-enabled route defines `fallbackUri: forward:/fallback/{service-name}`. On open circuit (or timeout), Spring Cloud Gateway forwards to the fallback controller.

**Fallback response (503):**
```json
{
  "timestamp": "2026-05-13T14:30:00.000Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "USER-SERVICE is temporarily unavailable",
  "service": "USER-SERVICE",
  "path": "/api/users/me",
  "traceId": "abc123"
}
```

### Resilience Configuration Profiles

#### Circuit Breaker

| Parameter | Dev (default) | Prod (default) | orderStrict (prod) | emailLenient (prod) |
|-----------|--------------|----------------|-------------------|-------------------|
| Sliding window | 10 | 100 | 100 | 100 |
| Min calls | 5 | 30 | 30 | 30 |
| Failure threshold | 70% | 50% | **30%** | 70% |
| Wait in open | 10s | 30s | **45s** | 15s |
| Half-open calls | — | 10 | **5** | 10 |

#### Retry

| Profile | Max Attempts | Wait |
|---------|-------------|------|
| `default` (dev) | 3 | 500ms |
| `default` (prod) | 2 | 300ms |
| `orderStrict` | 2 | 250ms |
| `emailLenient` | 4 (dev) / 2 (prod) | 600ms / inherited |

#### Bulkhead (max concurrent calls)

| Profile | Dev | Prod |
|---------|-----|------|
| `default` | 50 | 30 |
| `orderStrict` | 30 | 20 |
| `emailLenient` | 20 | 10 |

#### TimeLimiter

| Profile | Dev | Prod |
|---------|-----|------|
| `default` | 6s | 4s |
| `orderStrict` | 4s | 4s |
| `emailLenient` | 8s | 4s |

---

## 9. Service Discovery & Load Balancing

**Discovery:** Netflix Eureka Client (`@EnableDiscoveryClient`). Registered as `api-gateway`.

**Load balancing:** `spring-cloud-starter-loadbalancer` (Spring Cloud's built-in, replaces Ribbon). All routes use `lb://` URIs — Spring Cloud LoadBalancer resolves these via Eureka registry.

**Service name case inconsistency:**

| Route | URI | Case |
|-------|-----|------|
| auth_service | `lb://auth-client` | lowercase + hyphen |
| user_service | `lb://USER-SERVICE` | UPPERCASE |
| restaurant_service | `lb://RESTAURANT-SERVICE` | UPPERCASE |
| order_service | `lb://ORDER-SERVICE` | UPPERCASE |
| notification_service | `lb://notificationservice` | lowercase no hyphen |
| email_service | `lb://emailservice` | lowercase no hyphen |
| chat_service | `lb://CHAT-SERVICE` | UPPERCASE |

Eureka service IDs are case-insensitive in matching, so this works at runtime, but is inconsistent and confusing in configuration.

**Eureka config:**
- Dev: hardcoded `http://localhost:8761/eureka`
- Prod: `${EUREKA_ZONE}` — required env var

**HTTP client timeouts:**

| Setting | Dev | Prod |
|---------|-----|------|
| Connect timeout | 5000ms | 3000ms |
| Response timeout | 10s | 5s |

---

## 10. Logging & Monitoring

### Structured Logging

All log lines include `traceId` and `spanId` via MDC pattern:
```
%5p [api-gateway,traceId=%X{traceId:-},spanId=%X{spanId:-}]
```

**Log levels (base):**
- `org.springframework.cloud.gateway` → INFO
- `reactor.netty.http.client` → INFO
- `io.github.resilience4j` → INFO
- `io.opentelemetry` → DEBUG

### OpenTelemetry Tracing (`config/OpenTelemetryConfig.kt`)

**Resource attributes:**
- `service.name: api-gateway`
- `service.version: 1.0.0`
- `service.instance.id: {UUID-per-startup}`
- `service.namespace: ono-platform`
- `deployment.environment: {active-profile}`

**Propagation:** W3C Trace Context standard.  
**Exporter:** OTLP → `http://localhost:4317` (Jaeger or compatible collector).  
**Sampling:** 100% dev, 20% prod.

### Resilience Tracing (`config/ResilienceTracingConfig.kt`)

Bridges Resilience4j events to OTel spans:
- **Retry events** → span `resilience4j.retry` with tags `retry.name`, `retry.attempt`, `retry.exception`
- **Circuit breaker state transitions** → span `resilience4j.circuitbreaker.state.transition` + structured log `CB_STATE_CHANGE`
- **Circuit breaker errors** → span `resilience4j.circuitbreaker.error` + structured log `CB_ERROR`

### Prometheus Metrics

Endpoint: `/actuator/prometheus` (admin-only in prod).  
Configured percentiles for `http.server.requests`: p50, p90, p95, p99.  
Reactor Netty HTTP client metrics enabled.  
Global tag `application: api-gateway` on all metrics.

### Logbook HTTP Logging

`logbook-spring-boot-starter:3.9.0` logs full HTTP request/response bodies (configured via `logginglibrary`).

---

## 11. Architecture & Design Decisions

### Strengths

| Decision | Rationale |
|---------|----------|
| Reactive stack (WebFlux + Netty) | Non-blocking; gateway is I/O-bound; avoids thread-per-request overhead |
| Per-route resilience profiles | `orderStrict` / `emailLenient` correctly model different SLOs |
| Dual-layer rate limiting | Spring Cloud Gateway `RequestRateLimiter` (route-level) + `AuthPreFilter` custom limiter (user/IP-level) |
| Fail-safe blacklist check | Redis failure on `isBlacklisted` returns `true` (rejects) — correct security default |
| Fail-open rate limiter | Redis failure allows through — correct availability default |
| `scanBasePackages` restriction | Prevents logginglibrary Servlet classes from loading in WebFlux context |
| Named `GatewayRateLimiter` bean | Avoids Spring Cloud Gateway's auto-configured `redisRateLimiter` bean name collision |
| OTel W3C propagation | Enables end-to-end distributed trace across all services |

### Weaknesses

| Decision | Problem |
|---------|---------|
| `AuthPreFilter` combines 4 concerns | Auth validation, rate limiting, blacklist check, and header injection in one class — hard to test and extend |
| Hardcoded rate limit values in `AuthPreFilter` | 50 and 200 cannot be overridden via config without code change |
| Fixed-window rate limiting | Allows burst at window boundaries (up to 2× limit) — use sliding window or token bucket |
| `RouteValidator` uses string containment | `uri.contains("/auth/login")` is not path-safe |
| No JWT `iss`/`aud` validation | Tokens from any issuer with matching key are accepted |

---

## 12. Issues Found

### Critical

#### C1 — CORS Wildcard with `allowedOrigins: "*"` (`application.yaml:27`)

```yaml
allowedOrigins: "*"
allowedHeaders: "*"
```

Any origin — including malicious websites — can make cross-origin requests to the gateway. Combined with bearer tokens stored in `localStorage`, this enables CSRF-style token theft.

**Fix:** Replace with explicit origin whitelist from environment:
```yaml
allowedOrigins: "${CORS_ALLOWED_ORIGINS:https://app.sipsync.com}"
```

#### C2 — WebSocket Route Has Zero Filters (`application.yaml:227–230`)

Route `chat_service_ws` (`/ws/chat/**`) has no filters at all — no `AuthPreFilter`, no `RequestRateLimiter`, no `CircuitBreaker`. The comment "JWT validated in handler" is an informal contract, not enforcement. There is no mechanism ensuring the chat handler actually validates JWTs, and no protection against unauthenticated connection floods.

**Fix:** Apply `AuthPreFilter` to the WebSocket route. Spring Cloud Gateway supports filter application on WebSocket upgrade requests.

#### C3 — Email Service Missing Rate Limiting (`application.yaml:176–194`)

Route `email_service` is the only secured route without `RequestRateLimiter`. An authenticated user can trigger unlimited email sends through the gateway, enabling email spam or cost amplification attacks against the email provider.

**Fix:** Add `RequestRateLimiter` with conservative limits (e.g., 5 req/min):
```yaml
- name: RequestRateLimiter
  args:
    redis-rate-limiter.replenishRate: ${EMAIL_RATE_LIMIT:2}
    redis-rate-limiter.burstCapacity: ${EMAIL_BURST_CAPACITY:5}
```

---

### High

#### H1 — No JWT Issuer or Audience Validation (`config/JwtConfig.kt`)

`NimbusReactiveJwtDecoder` validates signature and expiry only. A token with the correct signing key but intended for a different service (different `aud`) is accepted. A token from an unintended issuer (different `iss`) with a compromised key is accepted.

**Fix:**
```kotlin
return NimbusReactiveJwtDecoder.withPublicKey(publicKey)
    .build()
    .also { decoder ->
        val validators = JwtValidators.createDefaultWithIssuer("https://auth.sipsync.com")
        decoder.setJwtValidator(validators)
    }
```

#### H2 — `RouteValidator` Uses String Containment, Not Path Matching (`security/RouteValidator.kt:18`)

```kotlin
return openApiEndpoints.none { uri.contains(it) }
```

`/api/auth/login-bypass`, `/internal/auth/login-reset`, or any path containing `/auth/login` will bypass `AuthPreFilter` rate limiting (treated as public). Conversely, a legitimate secured path containing any open-API string fragment would be misclassified.

**Fix:** Use `AntPathMatcher` or `PathPattern` for exact matching:
```kotlin
private val matcher = AntPathMatcher()
private val openApiPatterns = listOf("/api/auth/**", "/ws/**")
fun isSecured(uri: String) = openApiPatterns.none { matcher.match(it, uri) }
```

#### H3 — `AUTH_SERVICE_PUBLIC_KEY` Empty Default Causes Silent Runtime Failure (`config/JwtConfig.kt:13`)

```kotlin
@Value("\${security.jwt.public-key:}")  // default: empty string
```

If the env var is not set, the bean builds with an empty Base64 string. `KeyFactory.generatePublic()` will throw at first JWT validation attempt, not at startup. Every authenticated request fails with a cryptic `500` rather than a clear startup misconfiguration message.

**Fix:** Remove the default and add `@PostConstruct` validation, or require the property via `spring.config.import` with no fallback.

#### H4 — Hardcoded Rate Limits in `AuthPreFilter` (`AuthPreFilterGatewayFilterFactory.kt:50,78`)

```kotlin
.isAllowed(key, 50, 60)   // line 50 — public IP limit
.isAllowed(rateKey, 200, 60)  // line 78 — user limit
```

Cannot be changed without recompilation. The per-route `RequestRateLimiter` limits are environment-configurable, but these inner limits are invisible to operations teams.

**Fix:** Inject via `@Value` or `@ConfigurationProperties`:
```kotlin
@Value("\${gateway.rate-limit.public-ip:50}") private val publicIpLimit: Int
@Value("\${gateway.rate-limit.per-user:200}") private val perUserLimit: Int
```

#### H5 — No HTTPS Enforcement or HSTS Headers

No redirect from HTTP → HTTPS and no `Strict-Transport-Security` header. All traffic — including Bearer tokens — can flow in plaintext if the TLS termination layer is misconfigured or bypassed.

**Fix:** Add HSTS via a global response header filter:
```yaml
default-filters:
  - AddResponseHeader=Strict-Transport-Security, max-age=31536000; includeSubDomains
```
Or enforce via the load balancer / ingress layer.

---

### Medium

#### M1 — Fixed-Window Rate Limiting Algorithm Allows Double-Burst

`GatewayRateLimiter` uses a counter reset at fixed intervals. An attacker can send exactly `limit` requests at `59s` and `limit` requests at `61s` for a total of `2×limit` in two seconds — entirely within the algorithm's rules.

**Fix:** Replace with a sliding-window log or token-bucket algorithm. Spring Cloud Gateway's built-in `RequestRateLimiter` already uses a token bucket — the custom `GatewayRateLimiter` should match.

#### M2 — Service Name Casing Inconsistency in `lb://` URIs

`lb://auth-client`, `lb://USER-SERVICE`, `lb://notificationservice` — three different conventions. While Eureka matching is case-insensitive, the inconsistency causes confusion in logs and requires mental normalization.

**Fix:** Standardize to `UPPER-KEBAB-CASE` (matching Eureka's default `spring.application.name` uppercasing behavior).

#### M3 — `GatewayFallbackController` Does Not Validate Service Name Parameter (`resillience/GatewayFallbackController.kt:43`)

```kotlin
@GetMapping("/{service}")
fun serviceFallback(@PathVariable service: String): ...
```

`/fallback/../../../../etc` or `/fallback/<script>` reaches the controller. No whitelist validation. The current `uppercase()` call prevents case manipulation but not path traversal or injection.

**Fix:**
```kotlin
private val knownServices = setOf("AUTH-SERVICE","USER-SERVICE","ORDER-SERVICE","RESTAURANT-SERVICE","NOTIFICATION-SERVICE","EMAIL-SERVICE","CHAT-SERVICE")
if (normalizedService !in knownServices) return ResponseEntity.notFound().build()
```

#### M4 — Inconsistent Redis Failure Modes

| Operation | Failure Mode |
|-----------|-------------|
| Rate limiting (`isAllowed`) | **Fail-open** (allows request) |
| Blacklist check (`isBlacklisted`) | **Fail-safe** (rejects request) |
| JWT cache (`cacheIfAbsent`) | **Fail-open** (allows request) |

The mixed behavior is reasonable, but is undocumented and could surprise operators during a Redis outage — some requests pass, others are rejected, with no clear rule visible in the logs.

**Fix:** Document the chosen policy in code comments; add a Redis health indicator to the circuit breaker.

#### M5 — No Swagger / API Documentation

No `springdoc-openapi` dependency. No `/api-docs` endpoint. Operations teams and API consumers have no self-service API reference for gateway routes or downstream service contracts. (Resolved by Swagger integration — see §13.)

#### M6 — No Audit Log for Authentication Failures

Failed authentication, token blacklisting, and rate-limit rejections are only captured as distributed trace events (`tracer.event("token.blacklisted")`). There is no dedicated security audit log — making SIEM integration or compliance reporting difficult.

---

### Low

#### L1 — Dead Code: `RedisRateLimitService` + `RedisLuaConfig`

`redis/RedisRateLimitService.kt` and `redis/RedisLuaConfig.kt` are never injected or used. They register unnecessary Spring beans and confuse readers about which rate limiting path is active.

**Fix:** Delete both files. The active path is `GatewayRateLimiter`.

#### L2 — Unused `RedisKeys` Methods

`RedisKeys.jwtCacheByToken()` and `RedisKeys.blacklistToken()` are never called. Their corresponding Redis key patterns (`gateway:jwt:cache:token:*`, `gateway:jwt:blacklist:token:*`) are unused.

**Fix:** Remove unused methods, or document as reserved for future use.

#### L3 — Package Typo: `resillience` (double-L)

**Fix:** Rename package to `resilience`. Update all imports.

#### L4 — `ErrorHandlingFilter.buildErrorResponse` Uses String Interpolation for JSON

```kotlin
return """
    {
      "timestamp": "${Instant.now()}",
      "message": "$message",
      ...
    }
""".trimIndent()
```

If `message` or `path` contain `"`, `\`, or `\n`, the JSON becomes malformed. Should use Jackson's `ObjectMapper`.

#### L5 — HTTP Client Timeout Divergence (Dev: 10s, Prod: 5s)

Dev allows up to 10s upstream response time; prod cuts at 5s. A slow query that passes dev testing may time out in prod. Align dev to prod settings or document the delta.

---

## 13. Improvements Needed

### Immediate (Security / Correctness)

| # | Task | Effort |
|---|------|--------|
| 1 | Lock CORS to explicit origin whitelist (not `"*"`) | XS |
| 2 | Add `RequestRateLimiter` to `email_service` route | XS |
| 3 | Apply `AuthPreFilter` to `chat_service_ws` WebSocket route | S |
| 4 | Fix `RouteValidator` to use `AntPathMatcher` instead of `contains()` | S |
| 5 | Add JWT issuer + audience validation to `JwtConfig` | S |
| 6 | Validate service name in `GatewayFallbackController` | XS |
| 7 | Make rate-limit constants in `AuthPreFilter` configurable via properties | S |
| 8 | Fix `buildErrorResponse` to use Jackson serialization | XS |

### Short-Term (Quality / Observability)

| # | Task | Effort |
|---|------|--------|
| 9 | Delete `RedisRateLimitService` + `RedisLuaConfig` (dead code) | XS |
| 10 | Standardize `lb://` URI casing across all routes | XS |
| 11 | Fix package name typo `resillience` → `resilience` | XS |
| 12 | Add Swagger UI (`springdoc-openapi-starter-webflux-ui`) with downstream aggregation | M |
| 13 | Add security audit log for auth failures, blacklisted tokens, rate limit rejections | M |
| 14 | Remove unused `RedisKeys` methods | XS |

### Medium-Term (Architecture)

| # | Task | Effort |
|---|------|--------|
| 15 | Refactor `AuthPreFilter` — split into `JwtValidationFilter`, `RateLimitFilter`, `HeaderInjectionFilter` | L |
| 16 | Replace fixed-window rate limiter with sliding-window or token-bucket | M |
| 17 | Add HSTS header via global default filter | XS |
| 18 | Add Redis circuit breaker / health indicator | S |
| 19 | Align dev/prod HTTP client timeouts | XS |
| 20 | Fail fast on empty `AUTH_SERVICE_PUBLIC_KEY` at startup | S |

---

*Audit produced from static analysis of all source files. No runtime testing was performed.*
