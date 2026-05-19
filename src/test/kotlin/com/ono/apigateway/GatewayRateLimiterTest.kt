package com.ono.apigateway

import com.ono.apigateway.redis.GatewayRateLimiter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class GatewayRateLimiterTest {

    @Mock
    lateinit var redisTemplate: ReactiveRedisTemplate<String, String>

    @Mock
    lateinit var valueOps: ReactiveValueOperations<String, String>

    private lateinit var rateLimiter: GatewayRateLimiter

    @BeforeEach
    fun setUp() {
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        rateLimiter = GatewayRateLimiter(redisTemplate)
    }

    @Test
    fun `first request within limit is allowed and sets TTL`() {
        val key = "rate:ip:1.2.3.4"
        whenever(valueOps.increment(key)).thenReturn(Mono.just(1L))
        whenever(redisTemplate.expire(any(), any<Duration>())).thenReturn(Mono.just(true))

        StepVerifier.create(rateLimiter.isAllowed(key, limit = 10, windowSeconds = 60))
            .expectNext(true)
            .verifyComplete()
    }

    @Test
    fun `request within limit but after first is allowed without setting TTL again`() {
        val key = "rate:ip:1.2.3.4"
        whenever(valueOps.increment(key)).thenReturn(Mono.just(5L))

        StepVerifier.create(rateLimiter.isAllowed(key, limit = 10, windowSeconds = 60))
            .expectNext(true)
            .verifyComplete()
    }

    @Test
    fun `request at exact limit boundary is still allowed`() {
        val key = "rate:ip:1.2.3.4"
        whenever(valueOps.increment(key)).thenReturn(Mono.just(10L))

        StepVerifier.create(rateLimiter.isAllowed(key, limit = 10, windowSeconds = 60))
            .expectNext(true)
            .verifyComplete()
    }

    @Test
    fun `request over limit is rejected`() {
        val key = "rate:ip:1.2.3.4"
        whenever(valueOps.increment(key)).thenReturn(Mono.just(11L))

        StepVerifier.create(rateLimiter.isAllowed(key, limit = 10, windowSeconds = 60))
            .expectNext(false)
            .verifyComplete()
    }

    @Test
    fun `remainingQuota returns zero when fully consumed`() {
        val key = "rate:user:abc"
        whenever(valueOps.get(key)).thenReturn(Mono.just("10"))

        StepVerifier.create(rateLimiter.remainingQuota(key, limit = 10))
            .expectNext(0L)
            .verifyComplete()
    }

    @Test
    fun `remainingQuota returns full limit when key absent`() {
        val key = "rate:user:abc"
        whenever(valueOps.get(key)).thenReturn(Mono.empty())

        StepVerifier.create(rateLimiter.remainingQuota(key, limit = 50))
            .expectNext(50L)
            .verifyComplete()
    }

    @Test
    fun `remainingQuota clamps to zero when over limit`() {
        val key = "rate:user:abc"
        whenever(valueOps.get(key)).thenReturn(Mono.just("99"))

        StepVerifier.create(rateLimiter.remainingQuota(key, limit = 10))
            .expectNext(0L)
            .verifyComplete()
    }
}
