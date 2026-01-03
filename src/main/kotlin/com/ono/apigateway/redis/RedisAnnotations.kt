package com.ono.apigateway.redis

import org.springframework.beans.factory.annotation.Qualifier


@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FUNCTION
)
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class RateLimitRedis

@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FUNCTION
)
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class CacheRedis
