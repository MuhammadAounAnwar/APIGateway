package com.ono.apigateway.redis

object RedisKeys {

    fun rateByIp(ip: String) = "rate:ip:$ip"

    fun rateByUser(userId: String) = "rate:user:$userId"

    fun blacklistToken(token: String) = "blacklist:token:$token"
}
