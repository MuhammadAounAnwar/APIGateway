package com.ono.apigateway.redis

object RedisKeys {

    fun rateByIp(ip: String) = "rate:ip:$ip"
    fun jtiKey(jti: String) = "jwt:cache:jti:$jti"

    fun rateByUser(userId: String) = "rate:user:$userId"

    fun blacklistToken(token: String) = "blacklist:token:$token"

    fun jwtBlacklist(jti: String) = "blacklist:jti:$jti"

    fun jwtCache(token: String) = "jwt:cache:$token"
}
