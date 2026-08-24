package com.sesac.speechapp.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "jwt")
class JwtProperties {
    lateinit var secret: String
    var accessTokenExpiration: Long = 900000       // 15분
    var refreshTokenExpiration: Long = 604800000   // 7일
}