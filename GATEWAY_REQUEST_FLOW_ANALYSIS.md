# API Gateway Request Flow Analysis

## 🎯 High-Level Overview

The API Gateway is a **Spring Cloud Gateway** that:
- Routes requests to microservices based on URL patterns
- Enforces security (JWT validation, rate limiting)
- Provides resilience (circuit breaker, retry)
- Implements cross-cutting concerns (logging, correlation IDs, tracing)

---

## 🔧 Stack Versions

| Component | Version | Notes |
|-----------|---------|-------|
| Spring Boot | 3.3.5 | Spring Framework 6.1.x |
| Spring Cloud | 2023.0.1 | Gateway 4.1.1 (compatible with SF 6.1.x) |
| Java | 21 | LTS runtime |
| Kotlin | 1.9.24 | |

> **⚠️ Critical Compatibility Note**: Spring Cloud 2023.0.4+ (Gateway 4.1.6+) calls `HttpHeaders.headerSet()` which only exists in Spring Framework 6.2+. Spring Boot 3.3.x ships with SF 6.1.x. **Do not upgrade Spring Cloud past 2023.0.1 without also upgrading Spring Boot to 3.4.x+.**

---

## 📊 Complete Request Flow (Step-by-Step)

### **Example Request**
```
Client → GET /api/v1/public/onboarding/slides → API Gateway:8762
```

### **Step-by-Step Flow**

```
REQUEST ARRIVES AT PORT 8762
    ↓
┌─────────────────────────────────────────────────────────────────┐
│ 1. GLOBAL FILTERS (Applied to ALL requests)                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Order -300: CorrelationIdFilter  ← OUTERMOST GLOBAL FILTER    │
│  ├─ Generates or extracts X-Request-ID (UUID if absent)         │
│  ├─ Injects into request via ServerHttpRequestDecorator         │
│  │  (overrides getHeaders() with a fresh mutable copy)          │
│  └─ Echoes to response via response.beforeCommit {} hook        │
│                                                                  │
│  Order -250: AppCheckGatewayFilter (OPTIONAL)                   │
│  ├─ Only if Firebase is configured                              │
│  ├─ Validates X-Firebase-AppCheck header                        │
│  ├─ Calls Firebase API to verify token                          │
│  └─ Exempts: /actuator, /api/auth, /api/v1/guest, etc.        │
│                                                                  │
│  Order -200: InternalPathBlockFilter                            │
│  ├─ Blocks /internal/* paths                                    │
│  └─ Returns 403 FORBIDDEN if matched                            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────────┐
│ 2. SPRING SECURITY FILTER CHAIN                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  SecurityConfig.securityWebFilterChain():                        │
│                                                                  │
│  Check path against security rules:                              │
│  ├─ /api/auth/** → ✅ Permit all                                │
│  ├─ /api/v1/public/** → ✅ Permit all                           │
│  ├─ /api/v1/guest/** → ✅ Permit all                            │
│  ├─ /ws/** → ✅ Permit all                                      │
│  ├─ /fallback/** → ✅ Permit all (internal only)               │
│  ├─ /api-docs, /swagger-ui/** → ✅ Permit all                   │
│  ├─ /actuator/health → ✅ Permit all                            │
│  ├─ /actuator/** → ✅ Require ADMIN role                        │
│  ├─ OPTIONS → ✅ Permit all (CORS preflight)                   │
│  └─ Everything else → ❌ Requires authentication                │
│                                                                  │
│  If authentication needed:                                       │
│  ├─ Expects "Authorization: Bearer <JWT>"                       │
│  ├─ ReactiveJwtDecoder validates JWT signature                 │
│  ├─ JwtAuthenticationConverter extracts roles from "roles" claim│
│  └─ Stores Authentication in SecurityContext                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────────┐
│ 3. ROUTE MATCHING                                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  application.yaml routes are evaluated in order:                │
│  1. docs_auth_service → /gateway-api-docs/auth-service         │
│  2. docs_user_service → /gateway-api-docs/user-service         │
│  ... (other doc routes)                                         │
│  8a. docs_picklplay_backend → /gateway-api-docs/picklplay-...  │
│  8b. picklplay_backend → /api/v1/** ← MATCHES OUR REQUEST     │
│     Predicate: Path=/api/v1/**                                  │
│     URI: lb://PICKLPLAY-BACKEND (load balanced to service)      │
│                                                                  │
│  ✅ Route matched: picklplay_backend                            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────────┐
│ 4. ROUTE-LEVEL FILTERS (Applied to matched route)              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Route: picklplay_backend has these filters:                    │
│                                                                  │
│  Filter 1: AuthPreFilter                                        │
│  ├─ RouteValidator.isSecured(path) checks if public             │
│  ├─ Path: /api/v1/public/onboarding/slides                      │
│  ├─ Matches pattern: /api/v1/public/** ✅                       │
│  ├─ → PUBLIC ROUTE (no JWT needed)                              │
│  │                                                               │
│  │  For public routes:                                          │
│  │  ├─ Get client IP address                                    │
│  │  ├─ Create rate limit key: "rate_ip:{ip}"                    │
│  │  ├─ GatewayRateLimiter.isAllowed(key, limit=50, window=60s) │
│  │  ├─ If exceeds limit → 429 TOO_MANY_REQUESTS                │
│  │  ├─ Add X-RateLimit-* headers to response                    │
│  │  └─ Continue to next filter ✅                               │
│  │                                                               │
│  │  (For secured routes, JWT extraction & validation happens)   │
│  │                                                               │
│  Filter 2: Retry                                                │
│  ├─ Configured policy: "default"                                │
│  ├─ Retries on timeout or 5xx errors                            │
│  │                                                               │
│  Filter 3: CircuitBreaker                                       │
│  ├─ Monitors downstream service health                          │
│  ├─ Opens circuit if too many failures                          │
│  ├─ Fallback: forward:/fallback/picklplay-backend               │
│  │                                                               │
│  Filter 4 (implicit): Default Filters from application.yaml     │
│  ├─ RequestSize: max 10 MB                                      │
│  ├─ AddRequestHeader: X-Gateway: API-GATEWAY                    │
│  ├─ AddResponseHeader: X-Gateway-Version: v1                    │
│  ├─ RemoveResponseHeader: Server                                │
│  └─ DedupeResponseHeader: CORS headers                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────────┐
│ 5. SERVICE DISCOVERY & LOAD BALANCING                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  URI: lb://PICKLPLAY-BACKEND                                    │
│  ├─ "lb://" = Load Balanced URI (Spring Cloud Discovery)        │
│  ├─ Queries Eureka for service named "PICKLPLAY-BACKEND"       │
│  ├─ Eureka returns all instances:                               │
│  │  └─ 192.168.100.153:PICKLPLAY-BACKEND:8090 (status=UP)      │
│  ├─ Load balancer selects instance (round-robin)                │
│  └─ Actual destination: http://192.168.100.153:8090             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────────┐
│ 6. DOWNSTREAM HTTP REQUEST                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  GET http://192.168.100.153:8090/api/v1/public/onboarding/slides
│                                                                  │
│  Headers sent:                                                   │
│  ├─ X-Request-ID: <uuid> (from CorrelationIdFilter)             │
│  ├─ X-Gateway: API-GATEWAY (from default filters)               │
│  ├─ (No JWT header needed - public route)                       │
│  ├─ (No X-User-Id header - would be for secured routes)        │
│  │                                                               │
│  PicklePlay Backend processes request and returns:              │
│  └─ HTTP 200 OK with JSON body (onboarding slides)              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────────┐
│ 7. RESPONSE PROCESSING                                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Response from downstream service flows back through filters:    │
│  ├─ Filters run in reverse order on response                    │
│  ├─ CircuitBreaker records success (healing open circuits)      │
│  ├─ CorrelationIdFilter adds X-Request-ID to response header    │
│  ├─ Default filters add X-Gateway-Version, remove Server header │
│  └─ Response returned to client                                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
    ↓
HTTP 200 OK
{
  "slides": [
    { "id": 1, "title": "Welcome", ... },
    { "id": 2, "title": "Features", ... },
    ...
  ]
}
X-Request-ID: abc123def456
X-Gateway-Version: v1
X-RateLimit-Limit: 50
X-RateLimit-Remaining: 49
X-RateLimit-Reset: 1687500123
```

---

## 🔐 Secured Request Flow Example

### **Request to Secured Route**
```
Client → POST /api/v1/matches → API Gateway
With: Authorization: Bearer <JWT>
```

### **Differences in Secured Flow**

```
[Steps 1-3: Same as above until route matching]

Step 4 - AuthPreFilter (SECURED ROUTE):
├─ RouteValidator.isSecured("/api/v1/matches") → TRUE (not public)
├─ exchange.requireAuthContext() → Extracts JWT from Authorization header
├─ JWT validation:
│  ├─ Signature verification (using AUTH_SERVICE_PUBLIC_KEY)
│  ├─ Extract subject (user ID)
│  ├─ Extract claims (roles, tenantId, etc.)
│  └─ Extract jti (JWT ID for blacklist checking)
├─ Rate limiting by user:
│  ├─ Key: "rate_user:{userId}"
│  ├─ Limit: 200 requests per 60s
│  ├─ Check Redis for current count
│  └─ Increment counter
├─ Token blacklist check:
│  ├─ TokenStoreService.isBlacklisted(jti)
│  ├─ Queries Redis for token blacklist
│  └─ If blacklisted → 401 UNAUTHORIZED
├─ Token caching (if not blacklisted):
│  ├─ Cache jti in Redis with TTL = JWT expiration
│  └─ Speeds up subsequent validations
├─ Mutate request with headers:
│  ├─ X-User-Id: {userId}
│  ├─ X-User-Roles: {roles}
│  └─ X-Tenant-ID: {tenantId}
└─ Pass to next filter

[Continue to downstream service with these headers]
```

---

## 🏗️ Class & Component Purposes

### **Configuration Classes**

| Class | Purpose | Necessity |
|-------|---------|-----------|
| `SecurityConfig` | Defines Spring Security rules (auth paths, JWT validation) | ✅ **ESSENTIAL** |
| `JwtConfig` | Configures ReactiveJwtDecoder with public key & issuer | ✅ **ESSENTIAL** |
| `OpenTelemetryConfig` | Sets up distributed tracing | ⚠️ **OPTIONAL** (observability) |
| `ResilienceTracingConfig` | Integrates Resilience4j with tracing | ⚠️ **OPTIONAL** (observability) |
| `SwaggerConfig` | Configures Swagger UI for API docs aggregation | ⚠️ **OPTIONAL** (dev convenience) |
| `FirebaseAppCheckConfig` | Sets up Firebase App Check (if enabled) | ⚠️ **OPTIONAL** (security) |

### **Filter Classes**

| Class | Purpose | Order | Necessity |
|-------|---------|-------|-----------|
| `ErrorHandlingFilter` | Global error handler (catches all exceptions) | -1 | ✅ **ESSENTIAL** |
| `CorrelationIdFilter` | Adds X-Request-ID for tracing | -300 | ✅ **HIGHLY RECOMMENDED** |
| `AppCheckGatewayFilter` | Validates Firebase App Check tokens | -250 | ⚠️ **OPTIONAL** (only if using Firebase) |
| `InternalPathBlockFilter` | Blocks /internal/* paths (safety guard) | -200 | ✅ **RECOMMENDED** |
| `AuthPreFilterGatewayFilterFactory` | Rate limiting + JWT extraction + token validation | Route-level | ✅ **ESSENTIAL** |

### **Security & Validation Classes**

| Class | Purpose | Necessity |
|-------|---------|-----------|
| `RouteValidator` | Determines if a path is public or secured | ✅ **ESSENTIAL** |
| `JwtAuthenticationConverter` | Converts JWT claims to Spring Security authorities | ✅ **ESSENTIAL** |

### **Rate Limiting & Caching Classes**

| Class | Purpose | Necessity |
|-------|---------|-----------|
| `GatewayRateLimiter` | Fixed-window rate limiting with Redis | ✅ **ESSENTIAL** |
| `TokenStoreService` | Token blacklist & cache in Redis | ✅ **IMPORTANT** |
| `RedisKeys` | Helper to construct Redis key names | ✅ **NECESSARY** |

### **Resilience Classes**

| Class | Purpose | Necessity |
|-------|---------|-----------|
| `GatewayFallbackController` | Provides fallback responses when services are down | ✅ **IMPORTANT** |

### **Utilities**

| Class | Purpose | Necessity |
|-------|---------|-----------|
| `Extensions.kt` | Helper functions (requireAuthContext, error responses) | ✅ **ESSENTIAL** |

---

## 🔴 Potentially Unnecessary Components

### **1. `OpenTelemetryConfig` & `ResilienceTracingConfig`**

**Current use:**
- Distributed tracing with OpenTelemetry
- Configured to send traces to `http://localhost:4317` (Jaeger)

**Is it necessary?**
- **❌ NO** if you don't have an observability backend running
- **✅ YES** if you want to track request flows across services

**Impact if removed:**
- You won't have distributed traces
- `Tracer` dependency injection will fail → need to remove all `tracer.tag()` and `tracer.event()` calls
- Error handling will still work

**Recommendation:** Keep it (low overhead, high debugging value)

---

### **2. `FirebaseAppCheckConfig` & `AppCheckGatewayFilter`**

**Current use:**
- Validates Firebase App Check tokens on requests
- Only active if `FIREBASE_SERVICE_ACCOUNT_JSON` is set
- Exempts public paths and actuator endpoints

**Is it necessary?**
- **❌ NO** if you don't use Firebase App Check
- **✅ YES** if you want to verify requests come from your mobile app

**Current status in code:**
```kotlin
// application.yaml
firebase:
  disabled: true  // ← ALREADY DISABLED
```

**Impact if removed:**
- No Firebase App Check validation
- Mobile apps won't be verified as legitimate
- Less protection against spoofed requests

**Recommendation:** Remove if not using Firebase, or keep conditional on a feature flag

---

### **3. `SwaggerConfig`**

**Current use:**
- Aggregates API docs from all downstream services
- Provides Swagger UI at `/api-docs`
- Allows browsing all service APIs in one place

**Is it necessary?**
- **❌ NO** if you only use direct service docs
- **✅ YES** if you want a unified API documentation portal

**Impact if removed:**
- Can't browse aggregated API docs from gateway
- Still can access individual service docs directly
- Slightly faster startup

**Recommendation:** Keep it (useful for developers)

---

### **4. `TokenStoreService`**

**Current use:**
- Caches valid JWTs in Redis
- Checks if JWI (JWT ID) is blacklisted
- Helps revoke tokens immediately after logout

**Is it necessary?**
- **❌ PARTIALLY** - Token blacklist checking is essential, but caching might be overkill

**Current implementation:**
```kotlin
// AuthPreFilter.kt line 103
tokenStoreService.cacheIfAbsent(jti, userId, ttlSeconds)
```

**Issue:**
- Caching adds overhead with minimal benefit
- JWT's own expiration is sufficient
- Blacklist checking is critical for logout/revocation

**Recommendation:**
- **Keep blacklist checking** (line 89)
- **Consider removing caching** (line 103) - the `cacheIfAbsent` call seems redundant

---

### **5. `ResilienceTracingConfig.retry` with "default" and "orderStrict"**

**Current use:**
```yaml
# auth_service route
- name: Retry
  args:
    name: default

# order_service route
- name: Retry
  args:
    name: orderStrict
```

**Is it necessary?**
- **✅ YES** for transient failures, but **❌ OVERKILL** for some routes
- Auth failures shouldn't be retried (they won't succeed)
- Idempotent operations benefit from retries

**Recommendation:**
- Remove retry from auth routes (retrying failed auth won't help)
- Keep retry for GET/idempotent operations

---

## 🎯 Summary: Class Dependency Graph

```
┌─────────────────────────────────────────┐
│   Spring Boot Application Entry         │
│   (ApiGatewayApplication.kt)            │
└──────────────┬──────────────────────────┘
               │
               ├─→ SecurityConfig
               │   ├─→ ReactiveJwtDecoder (from JwtConfig)
               │   └─→ JwtAuthenticationConverter
               │
               ├─→ Global Filters
               │   ├─→ ErrorHandlingFilter
               │   ├─→ CorrelationIdFilter
               │   ├─→ AppCheckGatewayFilter (optional)
               │   └─→ InternalPathBlockFilter
               │
               ├─→ Route Filters (via application.yaml)
               │   ├─→ AuthPreFilterGatewayFilterFactory
               │   │   ├─→ RouteValidator
               │   │   ├─→ GatewayRateLimiter
               │   │   ├─→ TokenStoreService
               │   │   └─→ Tracer
               │   ├─→ Resilience4j (Retry, CircuitBreaker)
               │   └─→ Spring Cloud Gateway built-ins
               │
               ├─→ Fallback Handler
               │   └─→ GatewayFallbackController
               │
               └─→ Eureka Client Discovery
                   └─→ Service Registry (finds PICKLPLAY-BACKEND, etc.)
```

---

## 📝 Detailed Filter Chain Order

> **Lower order number = runs FIRST (outermost wrapper).** Each filter wraps all subsequent filters. A filter at -300 is outermost; its `onErrorResume` catches every downstream error.

```
Order  -300  │ CorrelationIdFilter  ← OUTERMOST
             │   Injects X-Request-ID into request via ServerHttpRequestDecorator.
             │   Echoes header to response via response.beforeCommit{}.
             │
Order  -250  │ AppCheckGatewayFilter (optional — validates Firebase App Check token)
             │
Order  -200  │ InternalPathBlockFilter (blocks /internal/* → 403)
             │
 ~Order -100 │ Spring Security WebFilter
             │   JWT decode + authorization rules
             │
Order    -1  │ ErrorHandlingFilter  ← INNERMOST GLOBAL FILTER
             │   Catches exceptions from route-level filters & downstream calls.
             │   Does NOT catch errors thrown by global filters above it
             │   (CorrelationId, AppCheck, InternalBlock, Spring Security).
             │
             │ ROUTE MATCHING (application.yaml routes evaluated in order)
             │
    Route-L  │ AuthPreFilter — sole rate limiter for most routes:
             │   • Public paths: IP-based rate limit (50 req/60s via Redis)
             │   • Secured paths: user-based rate limit (200 req/60s),
             │     JWT extraction from SecurityContext, blacklist check,
             │     header mutation (X-User-Id, X-User-Roles, X-Tenant-ID)
             │     via ServerHttpRequestDecorator pattern
    Route-L  │ RequestRateLimiter (built-in Spring — only on non-picklplay routes)
    Route-L  │ Retry (Resilience4j — retries 5xx / timeout)
    Route-L  │ CircuitBreaker (Resilience4j — fallback on repeated failures)
    Route-L  │ Default Filters (RequestSize 10MB, AddRequestHeader X-Gateway,
             │   AddResponseHeader X-Gateway-Version, RemoveResponseHeader Server,
             │   DedupeResponseHeader CORS)
             │
    ↓ Request forwarded to downstream service
    ↑ Response received
             │
    Route-L  │ Filters run in REVERSE on response
    Route-L  │ ...
             │
             │ Global filters run in REVERSE on response
             │   (ErrorHandlingFilter first, CorrelationIdFilter last)
             │
             └─→ Response sent to client

IMPORTANT — picklplay_backend route does NOT use RequestRateLimiter.
AuthPreFilter handles all rate limiting for that route, preventing
double-counting and conflicts on public sub-paths (/api/v1/public/**).
```

---

## ✅ Critical Issues Fixed (Production-Ready State)

The gateway was previously returning 500/503 on all routes. Root causes diagnosed and fixed:

### 1. `NoSuchMethodError: HttpHeaders.headerSet()` — Primary Root Cause
- **Cause**: Spring Cloud 2023.0.4 (Gateway 4.1.6) calls `HttpHeaders.headerSet()` which only exists in Spring Framework 6.2+. Spring Boot 3.3.5 ships SF 6.1.x.
- **Fix**: Downgraded Spring Cloud → `2023.0.1` (bundles Gateway 4.1.1, compatible with SF 6.1.x).

### 2. `UnsupportedOperationException: ReadOnlyHttpHeaders.put()` in `CorrelationIdFilter`
- **Cause**: SF 6.1.14 tightened `ReadOnlyHttpHeaders`. Calling `.header()` on the request mutate builder internally touched read-only headers.
- **Fix**: `CorrelationIdFilter` now uses `ServerHttpRequestDecorator` overriding `getHeaders()` with a fresh mutable `HttpHeaders` copy.

### 3. `UnsupportedOperationException` in `AuthPreFilterGatewayFilterFactory.mutate()`
- **Cause**: Same as above — `exchange.request.mutate().header(...)` for X-User-Id/X-User-Roles/X-Tenant-ID headers.
- **Fix**: `mutate()` now also uses `ServerHttpRequestDecorator` pattern.

### 4. Response headers written after commit in `CorrelationIdFilter`
- **Cause**: `Mono.fromRunnable { response.headers.set(...) }.then(chain.filter())` ran after the response body was written; headers were sealed.
- **Fix**: Changed to `response.beforeCommit { response.headers.set(...); Mono.empty() }`.

### 5. Duplicate rate limiting on `picklplay_backend` route
- **Cause**: Both `AuthPreFilter` (custom) and Spring's `RequestRateLimiter` (built-in) were both applied, causing conflicts on public sub-paths.
- **Fix**: Removed `RequestRateLimiter` from the `picklplay_backend` route. `AuthPreFilter` handles all rate limiting for this route.

---

## ✅ Recommended Cleanup

### **Remove (Safe to Delete)**

1. **OpenTelemetryConfig** - if you don't have Jaeger running
2. **FirebaseAppCheckConfig** - if not using Firebase
3. **AppCheckGatewayFilter** - if not using Firebase
4. **Token caching in AuthPreFilter** (line 102-104) - redundant with JWT TTL
5. **Retry on /api/auth/** routes - won't help with auth failures

### **Keep (Essential)**

1. SecurityConfig
2. RouteValidator
3. AuthPreFilterGatewayFilterFactory
4. ErrorHandlingFilter
5. InternalPathBlockFilter
6. GatewayRateLimiter
7. TokenStoreService (for token blacklisting)
8. CorrelationIdFilter

