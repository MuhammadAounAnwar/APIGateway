package com.ono.apigateway.redis

object RedisKeys {

    // ---------------------------------------------------------------------
    // Global namespace prefix (prevents collision with other services)
    // ---------------------------------------------------------------------
    private const val PREFIX = "gateway"

    // ---------------------------------------------------------------------
    // Rate Limiting Keys
    // ---------------------------------------------------------------------

    fun rateByIp(ip: String): String =
        "$PREFIX:rate:ip:$ip"

    fun rateByUser(userId: String): String =
        "$PREFIX:rate:user:$userId"

    // ---------------------------------------------------------------------
    // JWT Cache Keys
    // ---------------------------------------------------------------------

    fun jwtCacheByJti(jti: String): String =
        "$PREFIX:jwt:cache:jti:$jti"

    fun jwtCacheByToken(token: String): String =
        "$PREFIX:jwt:cache:token:$token"

    // ---------------------------------------------------------------------
    // JWT Blacklist Keys
    // ---------------------------------------------------------------------

    fun jwtBlacklist(jti: String): String =
        "$PREFIX:jwt:blacklist:jti:$jti"

    fun blacklistToken(token: String): String =
        "$PREFIX:jwt:blacklist:token:$token"

    // ---------------------------------------------------------------------
    // Utility (optional future use)
    // ---------------------------------------------------------------------

    fun build(vararg parts: String): String =
        listOf(PREFIX, *parts).joinToString(":")
}
