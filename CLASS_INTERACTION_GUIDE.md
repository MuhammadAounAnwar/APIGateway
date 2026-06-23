# API Gateway: Class Interactions & Code Flow

> **Stack**: Spring Boot 3.3.5 · Spring Cloud 2023.0.1 · Gateway 4.1.1 · Java 21
>
> **Critical pattern**: Header injection uses `ServerHttpRequestDecorator` (overrides `getHeaders()` with a fresh mutable copy). Direct `request.mutate().header()` throws `UnsupportedOperationException` on Spring Framework 6.1.14+ due to tightened `ReadOnlyHttpHeaders` enforcement. Response headers use `response.beforeCommit {}` to ensure they're set before the response is committed.

## 🎯 Class Dependency Chain

### **How Classes Call Each Other (Step-by-Step)**

```
Client sends request
    ↓
Spring Cloud Gateway bootstraps (ApiGatewayApplication.kt)
    ├─ Instantiates SecurityConfig
    │  ├─ Creates ReactiveJwtDecoder (from JwtConfig)
    │  └─ Creates JwtAuthenticationConverter
    │
    ├─ Instantiates all Global Filters
    │  ├─ ErrorHandlingFilter
    │  ├─ CorrelationIdFilter
    │  ├─ AppCheckGatewayFilter (if FirebaseApp bean exists)
    │  └─ InternalPathBlockFilter
    │
    └─ Instantiates Route Filters (from application.yaml)
       ├─ AuthPreFilterGatewayFilterFactory
       │  ├─ Creates RouteValidator instance
       │  ├─ Creates GatewayRateLimiter instance
       │  ├─ Creates TokenStoreService instance
       │  └─ Injects Tracer instance
       │
       ├─ Built-in Spring Cloud Gateway filters
       │  └─ RequestRateLimiter (auth/user/order routes), Retry, CircuitBreaker, etc.
       │     Note: picklplay_backend route does NOT use RequestRateLimiter;
       │     AuthPreFilter handles all rate limiting for that route.
       │
       └─ GatewayFallbackController (fallback responses)
```

---

## 📝 Real Code Examples

### **Example 1: Public Route Request Flow**

```
CLIENT REQUEST:
GET /api/v1/public/onboarding/slides HTTP/1.1
Host: localhost:8762
X-Request-ID: <no header>


STEP 1: CorrelationIdFilter.filter()
────────────────────────────────────
// Uses ServerHttpRequestDecorator pattern (Spring Framework 6.1.14+ compatibility).
// Direct mutation via request.mutate().header() throws UnsupportedOperationException
// because ReadOnlyHttpHeaders enforcement was tightened in SF 6.1.14.
private fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
    val requestId = exchange.request.headers.getFirst("X-Request-ID")
        ?: UUID.randomUUID().toString()  // ← Generate new UUID

    // Echo to response BEFORE headers are committed (beforeCommit fires at the right time)
    exchange.response.beforeCommit {
        exchange.response.headers.set("X-Request-ID", requestId)
        Mono.empty()
    }

    // Decorator: creates a fresh mutable HttpHeaders copy with the X-Request-ID injected
    val decoratedRequest = object : ServerHttpRequestDecorator(exchange.request) {
        override fun getHeaders(): HttpHeaders {
            val headers = HttpHeaders()
            super.getHeaders().forEach { name, values ->
                headers[name] = ArrayList(values)
            }
            headers.set("X-Request-ID", requestId)
            return HttpHeaders.readOnlyHttpHeaders(headers)
        }
    }

    return chain.filter(exchange.mutate().request(decoratedRequest).build())
}

REQUEST AFTER FILTER:
GET /api/v1/public/onboarding/slides HTTP/1.1
X-Request-ID: 550e8400-e29b-41d4-a716-446655440000


STEP 2: InternalPathBlockFilter.filter()
─────────────────────────────────────────
override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
    val path = exchange.request.uri.path  // "/api/v1/public/onboarding/slides"
    if (path.startsWith("/internal/")) {
        // ↑ Does NOT match - continue
        exchange.response.statusCode = HttpStatus.FORBIDDEN
        return response.writeWith(...)
    }
    return chain.filter(exchange)  // ← Continue to next filter
}


STEP 3: SecurityConfig.securityWebFilterChain()
────────────────────────────────────────────────
authorizeExchange { exchanges ->
    exchanges
        .pathMatchers("/api/auth/**").permitAll()
        .pathMatchers("/api/v1/public/**").permitAll()  // ← MATCHES!
        // ↓ No JWT validation needed
        .pathMatchers("/api/v1/guest/**").permitAll()
        .pathMatchers("/ws/**").permitAll()
        .pathMatchers("/fallback/**").permitAll()
        .pathMatchers("/api-docs/**").permitAll()
        .pathMatchers(HttpMethod.OPTIONS).permitAll()
        .anyExchange().authenticated()  // Everything else needs auth
}

// Result: Authentication check SKIPPED for public paths


STEP 4: Route Matching (application.yaml)
──────────────────────────────────────────
Routes evaluated in order:
  [routes 1-7: don't match /api/v1/public/...]

  Route 8b: picklplay_backend
    Predicates:
      - Path=/api/v1/**  ← MATCHES!
    URI: lb://PICKLPLAY-BACKEND
    Filters:
      - name: AuthPreFilter
      - name: Retry
      - name: CircuitBreaker


STEP 5: AuthPreFilterGatewayFilterFactory.apply()
──────────────────────────────────────────────────
override fun apply(config: Config): GatewayFilter {
    return GatewayFilter { exchange, chain ->
        val request = exchange.request
        val path = request.uri.path  // "/api/v1/public/onboarding/slides"
        val ip = request.remoteAddress?.address?.hostAddress  // "192.168.1.100"

        // Check if this path is secured
        if (!routeValidator.isSecured(path)) {
            // ↑ isSecured() checks against openApiPatterns
            // Pattern "/api/v1/public/**" matches!
            // Result: !isSecured = true (it IS public)

            val key = RedisKeys.rateByIp(ip)  // "rate_ip:192.168.1.100"

            return@GatewayFilter rateLimiter
                .isAllowed(key, publicIpRateLimit=50, 60)
                //      ↓
                //      Redis INCR on key
                //      If count > 50 → return false
                //      Otherwise → true
                .flatMap { allowed ->
                    tracer.tagRateLimit("ip", key, allowed)

                    if (!allowed) {
                        return@flatMap tooManyRequests()  // ← 429 TOO_MANY_REQUESTS
                    }

                    // Rate limit OK - add headers and continue
                    addRateLimitHeaders(exchange, key, 50)
                        .then(chain.filter(exchange))
                        //  ↑ Pass to next filter (Retry)
                }
        }
    }
}

// For this request: Rate limiting passed, continue


STEP 6: Retry Filter (from application.yaml)
─────────────────────────────────────────────
Filter config:
  - name: Retry
    args:
      name: default

// Behavior: If downstream returns 5xx or timeout, retry up to 3 times
// For this flow: Still in flight, no retry needed yet


STEP 7: CircuitBreaker Filter
──────────────────────────────
Filter config:
  - name: CircuitBreaker
    args:
      name: default
      fallbackUri: forward:/fallback/picklplay-backend

// Monitors if PICKLPLAY-BACKEND is healthy
// For this flow: Service should be healthy, forwards request


STEP 8: Service Discovery & Load Balancing
───────────────────────────────────────────
RouteProperties {
    uri: "lb://PICKLPLAY-BACKEND"  // ← Load balanced URI
}

LoadBalancerClient resolves:
  1. Query Eureka: GET /eureka/apps/PICKLPLAY-BACKEND
  2. Eureka returns: [
       {
         "app": "PICKLPLAY-BACKEND",
         "instance": {
           "ipAddr": "192.168.100.153",
           "port": 8090,
           "status": "UP"
         }
       }
     ]
  3. Round-robin selector picks: 192.168.100.153:8090
  4. Final URL: http://192.168.100.153:8090/api/v1/public/onboarding/slides


STEP 9: Downstream Request Sent
────────────────────────────────
GET http://192.168.100.153:8090/api/v1/public/onboarding/slides HTTP/1.1
X-Request-ID: 550e8400-e29b-41d4-a716-446655440000
X-Gateway: API-GATEWAY
(no Authorization header - public route)


STEP 10: PicklePlay Backend Response
─────────────────────────────────────
HTTP 200 OK
Content-Type: application/json

{
  "slides": [
    { "id": 1, "title": "Welcome", ... },
    { "id": 2, "title": "Features", ... },
    { "id": 3, "title": "Get Started", ... },
    { "id": 4, "title": "Download", ... }
  ]
}


STEP 11: Response Processing (Filters in REVERSE)
──────────────────────────────────────────────────
CircuitBreaker:
  ├─ Record success
  ├─ Heal circuit if it was open
  └─ Continue

Retry:
  └─ No-op (successful response, no retry)

AuthPreFilter (on response):
  └─ No-op (response phase)

Default Filters (on response):
  ├─ AddResponseHeader: X-Gateway-Version: v1
  ├─ RemoveResponseHeader: Server
  └─ DedupeResponseHeader: CORS headers

CorrelationIdFilter (on response):
  └─ Add X-Request-ID to response header (already done)

ErrorHandlingFilter:
  └─ No exception, pass through


STEP 12: Response to Client
───────────────────────────
HTTP 200 OK
Content-Type: application/json
X-Request-ID: 550e8400-e29b-41d4-a716-446655440000
X-Gateway: API-GATEWAY
X-Gateway-Version: v1
X-RateLimit-Limit: 50
X-RateLimit-Remaining: 49
X-RateLimit-Reset: 1687500123

{
  "slides": [
    { "id": 1, "title": "Welcome", ... },
    { "id": 2, "title": "Features", ... },
    { "id": 3, "title": "Get Started", ... },
    { "id": 4, "title": "Download", ... }
  ]
}
```

---

### **Example 2: Secured Route Request Flow**

```
CLIENT REQUEST:
POST /api/v1/matches HTTP/1.1
Host: localhost:8762
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...


STEP 3 (Different): SecurityConfig.securityWebFilterChain()
──────────────────────────────────────────────────────────
authorizeExchange { exchanges ->
    exchanges
        .pathMatchers("/api/auth/**").permitAll()
        .pathMatchers("/api/v1/public/**").permitAll()  // ← Does NOT match
        .pathMatchers("/api/v1/guest/**").permitAll()   // ← Does NOT match
        ...
        .anyExchange().authenticated()  // ← THIS MATCHES!
}

Since no pattern matched:
  → OAuth2 JWT validation triggered
  → Extract Authorization header
  → ReactiveJwtDecoder.decode(token)
     ├─ Validate signature (using AUTH_SERVICE_PUBLIC_KEY)
     ├─ Check expiration
     ├─ Check issuer
     └─ Create Jwt object if valid

  → JwtAuthenticationConverter.convert(jwt)
     ├─ Extract roles claim: jwt.getClaimAsStringList("roles")
     │  Result: ["USER", "MATCH_ORGANIZER"]
     ├─ Convert to Spring Security authorities
     │  Result: ["ROLE_USER", "ROLE_MATCH_ORGANIZER"]
     └─ Create Authentication with these roles

  → Store Authentication in ReactiveSecurityContextHolder
  → If invalid: Return 401 UNAUTHORIZED


STEP 4 (Different): Route Matching
──────────────────────────────────
Route 8b: picklplay_backend
  Predicates:
    - Path=/api/v1/**  ← MATCHES!


STEP 5 (Different): AuthPreFilterGatewayFilterFactory.apply()
──────────────────────────────────────────────────────────────
override fun apply(config: Config): GatewayFilter {
    return GatewayFilter { exchange, chain ->
        val request = exchange.request
        val path = request.uri.path  // "/api/v1/matches"

        // Check if this path is secured
        if (!routeValidator.isSecured(path)) {
            // Pattern "/api/v1/public/**" does NOT match
            // Pattern "/api/v1/guest/**" does NOT match
            // Result: isSecured = true
            // So: !isSecured = false
            // ↓ SKIP public route handling
        }

        // SECURED ROUTES ─────────────────────────────
        exchange.requireAuthContext()
            .flatMap { authContext ->  // ← Authentication extracted here
                val jwt = authContext.jwt
                val roles = authContext.roles  // "ROLE_USER,ROLE_MATCH_ORGANIZER"

                val userId = jwt.subject  // "user-456" (sub claim)
                val jti = jwt.id         // "jwt-id-12345" (jti claim)

                // Emit tracing
                tracer.event("authentication.success")
                tracer.tag("auth.user.id", userId)
                tracer.tag("auth.roles", roles)

                // Rate limit by user
                val rateKey = RedisKeys.rateByUser(userId)  // "rate_user:user-456"

                rateLimiter.isAllowed(rateKey, perUserRateLimit=200, 60)
                    .flatMap outer@{ allowed ->
                        tracer.tagRateLimit("user", rateKey, allowed)

                        if (!allowed) {
                            return@outer tooManyRequests()  // ← 429
                        }

                        // Check token blacklist
                        tokenStoreService.isBlacklisted(jti)
                            .flatMap inner@{ blacklisted ->
                                if (blacklisted) {
                                    tracer.event("token.blacklisted")
                                    return@inner unauthorized("Token blacklisted")  // ← 401
                                }

                                // Cache token (if TTL > 0)
                                val ttlSeconds = jwt.expiresAt?.let {
                                    Duration.between(Instant.now(), it).seconds
                                } ?: 0

                                val cacheMono = if (ttlSeconds > 0) {
                                    tokenStoreService.cacheIfAbsent(jti, userId, ttlSeconds)
                                } else Mono.empty()

                                // Extract tenant
                                val tenantId = jwt.getClaim<String>("tenantId") ?: "default"

                                // Mutate request with auth headers
                                cacheMono.then(
                                    addRateLimitHeaders(exchange, rateKey, 200)
                                        .then(chain.filter(mutate(exchange, userId, roles, tenantId)))
                                        //                      ↓
                                        //    This adds headers:
                                        //    X-User-Id: user-456
                                        //    X-User-Roles: ROLE_USER,ROLE_MATCH_ORGANIZER
                                        //    X-Tenant-ID: tenant-789
                                )
                            }
                    }
            }
    }
}

private fun mutate(
    exchange: ServerWebExchange,
    userId: String,
    roles: String,
    tenantId: String
): ServerWebExchange {
    // Decorator pattern: avoids ReadOnlyHttpHeaders mutation issues
    // (Spring Framework 6.1.14+ tightened enforcement).
    // Creates a fresh mutable HttpHeaders copy with the auth headers injected.
    val decoratedRequest = object : ServerHttpRequestDecorator(exchange.request) {
        override fun getHeaders(): HttpHeaders {
            val headers = HttpHeaders()
            super.getHeaders().forEach { name, values ->
                headers[name] = ArrayList(values)
            }
            headers.set("X-User-Id", userId)
            headers.set("X-User-Roles", roles)
            headers.set("X-Tenant-ID", tenantId)
            return HttpHeaders.readOnlyHttpHeaders(headers)
        }
    }
    return exchange.mutate().request(decoratedRequest).build()
}


STEP 9: Downstream Request Sent (with auth headers)
────────────────────────────────────────────────────
POST http://192.168.100.153:8090/api/v1/matches HTTP/1.1
X-Request-ID: 550e8400-e29b-41d4-a716-446655440000
X-Gateway: API-GATEWAY
X-User-Id: user-456
X-User-Roles: ROLE_USER,ROLE_MATCH_ORGANIZER
X-Tenant-ID: tenant-789

Content-Type: application/json
{
  "homeTeam": "Team A",
  "awayTeam": "Team B"
}
```

---

## 🚨 Error Handling Example

```
SCENARIO: PicklePlay Backend is DOWN

STEP 8: Service Discovery
─────────────────────────
LoadBalancerClient queries Eureka
  ↓
Eureka responds: No healthy instances for PICKLPLAY-BACKEND
  ↓
LoadBalancer can't find endpoint
  ↓
Timeout or Connection Refused exception


STEP 6: Retry Filter detects failure
────────────────────────────────────
Retry policy "default":
  ├─ Retry on: 5xx, timeout
  ├─ Max attempts: 3
  ├─ Attempt 1: Connection refused ✗
  ├─ Wait 100ms
  ├─ Attempt 2: Connection refused ✗
  ├─ Wait 200ms
  ├─ Attempt 3: Connection refused ✗
  └─ After max retries: Throw exception


STEP 7: CircuitBreaker detects failure
──────────────────────────────────────
CircuitBreaker state machine:
  ├─ CLOSED (normal) → [Too many failures detected]
  ├─ OPEN (failing) → [Fallback activated]
  └─ HALF_OPEN (testing recovery)

When OPEN:
  fallbackUri: forward:/fallback/picklplay-backend


STEP 7b: ErrorHandlingFilter catches exception
───────────────────────────────────────────────
override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
    return chain.filter(exchange)
        .onErrorResume { ex ->  // ← Catches all downstream exceptions
            handleException(exchange, ex)
        }
}

private fun handleException(exchange: ServerWebExchange, ex: Throwable): Mono<Void> {
    val span = tracer.currentSpan()
    span?.error(ex)
    span?.tag("error", "true")
    span?.tag("error.type", ex.javaClass.simpleName)

    val status = resolveHttpStatus(ex)
    // Result: HttpStatus.SERVICE_UNAVAILABLE (503)

    exchange.response.statusCode = status
    exchange.response.headers.contentType = MediaType.APPLICATION_JSON

    val body = buildErrorResponse(
        exchange = exchange,
        status = status,
        message = safeMessage(ex),
        traceId = span?.context()?.traceId()
    )

    val buffer = exchange.response
        .bufferFactory()
        .wrap(body.toByteArray(StandardCharsets.UTF_8))

    return exchange.response.writeWith(Mono.just(buffer))
}


OR Alternative: CircuitBreaker forwards to fallback
────────────────────────────────────────────────────
CircuitBreaker detects OPEN state
  └─ Forward request to: /fallback/picklplay-backend

GatewayFallbackController.serviceFallback() receives request
  ├─ @PathVariable service: "picklplay-backend"
  ├─ Normalize: "PICKLPLAY-BACKEND"
  ├─ Check knownServices set: YES, valid service
  ├─ Build response:
  │  {
  │    "timestamp": "2024-06-23T10:30:45Z",
  │    "status": 503,
  │    "error": "Service Unavailable",
  │    "message": "PICKLPLAY-BACKEND is temporarily unavailable",
  │    "service": "PICKLPLAY-BACKEND",
  │    "path": "/api/v1/matches",
  │    "traceId": "550e8400-e29b-41d4-a716-446655440000"
  │  }
  └─ Return: ResponseEntity(status=503, body=response)


CLIENT RESPONSE
───────────────
HTTP 503 SERVICE_UNAVAILABLE
Content-Type: application/json
X-Request-ID: 550e8400-e29b-41d4-a716-446655440000

{
  "timestamp": "2024-06-23T10:30:45.123Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "PICKLPLAY-BACKEND is temporarily unavailable",
  "service": "PICKLPLAY-BACKEND",
  "path": "/api/v1/matches",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

## 🔄 Token Blacklist / Revocation Flow

```
USER LOGS OUT
  ↓
Auth Service:
  ├─ Receives POST /api/auth/logout
  ├─ Extracts JWT from request
  ├─ Gets jti claim (unique token ID)
  └─ Publishes event: "TokenBlacklisted" with jti

Gateway subscribes to event:
  ├─ Message: { jti: "abc-123", userId: "user-456" }
  ├─ Calls TokenStoreService.blacklist(jti)
  │  └─ Redis: SET "blacklist:abc-123" 1 EX <token-ttl>
  └─ Token added to Redis blacklist set

NEXT REQUEST with blacklisted token
  ↓
AuthPreFilter checks token:
  ├─ Extract jti from JWT
  ├─ Call TokenStoreService.isBlacklisted("abc-123")
  │  └─ Query Redis: GET "blacklist:abc-123"
  │     └─ Returns: "1" (found in blacklist)
  ├─ Result: blacklisted = true
  └─ Return 401 UNAUTHORIZED "Token blacklisted"

CLIENT RESPONSE
  ↓
HTTP 401 UNAUTHORIZED
{
  "error": "UNAUTHORIZED",
  "message": "Token blacklisted",
  "timestamp": "...",
  "path": "/api/v1/matches",
  "traceId": "..."
}
```

---

## 💾 Rate Limiting Implementation

```
GatewayRateLimiter.isAllowed(key: String, limit: Int, windowSeconds: Long)
  ├─ Redis operation: INCR key
  │  └─ Atomically increment counter
  │
  ├─ If count == 1 (first request in window):
  │  └─ Redis: EXPIRE key windowSeconds
  │     └─ Set TTL for window (e.g., 60 seconds)
  │
  ├─ If count > limit:
  │  └─ Return: false (rate limited)
  │
  └─ Otherwise:
     └─ Return: true (allowed)


EXAMPLE WITH IP-BASED RATE LIMITING (50 req/60s):

Request 1: GET /api/v1/public/slides
  ├─ IP: 192.168.1.100
  ├─ Key: "rate_ip:192.168.1.100"
  ├─ INCR → count = 1
  ├─ EXPIRE 60
  └─ Allowed: YES

Request 2-50: (same IP)
  ├─ Key: "rate_ip:192.168.1.100"
  ├─ INCR → count = 2-50
  └─ Allowed: YES

Request 51: (same IP within 60s)
  ├─ Key: "rate_ip:192.168.1.100"
  ├─ INCR → count = 51
  ├─ count (51) > limit (50)? YES
  └─ Allowed: NO → 429 TOO_MANY_REQUESTS

Request after 60s:
  ├─ Key: "rate_ip:192.168.1.100" (expired)
  ├─ INCR → count = 1 (fresh window)
  └─ Allowed: YES


RESPONSE HEADERS ADDED:
X-RateLimit-Limit: 50
X-RateLimit-Remaining: {limit - current_count}
X-RateLimit-Reset: {current_time + remaining_ttl} (unix timestamp)
```

---

## 📊 When Routes are Evaluated

```
application.yaml routes are evaluated in ORDER (top to bottom):

GET /gateway-api-docs/picklplay-backend
  ↓ Route 1-7: Don't match
  ↓ Route 8a: docs_picklplay_backend matches
     Path=/gateway-api-docs/picklplay-backend ✅
     Filters: RewritePath
     URI: lb://PICKLPLAY-BACKEND
     → Rewrite to /v3/api-docs
     → Forward to service

GET /api/v1/public/onboarding/slides
  ↓ Route 1-7: Don't match
  ↓ Route 8a: docs_picklplay_backend doesn't match (different path)
  ↓ Route 8b: picklplay_backend matches
     Path=/api/v1/** ✅
     Filters: AuthPreFilter, Retry, CircuitBreaker
     URI: lb://PICKLPLAY-BACKEND
     → Forward to service

POST /api/venues
  ↓ Route 1-8b: Don't match
  ↓ Route 9: spozon_user_paths doesn't match
  ↓ Route 10: spozon_backend matches
     Path=/api/venues/** ✅
     URI: lb://SPOZON-BACKEND
     → Forward to service

GET /api/auth/login
  ↓ Route 1: auth_service matches
     Path=/api/auth/** ✅
     URI: lb://AUTH-CLIENT
     Filters: RequestRateLimiter, Retry, CircuitBreaker
     → Forward to service
```

---

## ✅ Summary: Which Classes Do What

| Class | Called By | Calls | Purpose |
|-------|-----------|-------|---------|
| **ApiGatewayApplication** | Spring Boot | All configs & beans | Entry point, enables service discovery |
| **SecurityConfig** | Spring Security | JwtConfig, JwtAuthenticationConverter | JWT validation & authorization rules |
| **JwtConfig** | SecurityConfig | External: Auth Service public key | Configures JWT decoder |
| **RouteValidator** | AuthPreFilter | Redis | Determines if path is public or secured |
| **AuthPreFilterGatewayFilterFactory** | Gateway routes | RouteValidator, GatewayRateLimiter, TokenStoreService, Tracer, ServerHttpRequestDecorator | Rate limiting, JWT extraction, token blacklist check, header mutation via decorator |
| **GatewayRateLimiter** | AuthPreFilter | Redis | Fixed-window rate limiting |
| **TokenStoreService** | AuthPreFilter | Redis | Token blacklist & caching |
| **ErrorHandlingFilter** | Spring Security | ObjectMapper, Tracer | Global error handler |
| **CorrelationIdFilter** | Spring Gateway | UUID, ServerHttpRequestDecorator | Generates X-Request-ID; injects via decorator; echoes to response via beforeCommit |
| **InternalPathBlockFilter** | Spring Gateway | None | Blocks /internal/* paths |
| **AppCheckGatewayFilter** | Spring Gateway (optional) | Firebase API, HttpClient | Validates App Check tokens |
| **GatewayFallbackController** | CircuitBreaker | Tracer | Fallback responses for downstream failures |
| **Tracer** | All filters | OpenTelemetry | Distributed tracing |

