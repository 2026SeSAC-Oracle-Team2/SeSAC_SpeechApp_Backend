package com.sesac.speechapp.security

import com.sesac.speechapp.config.JwtProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.util.*
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    private val jwtProperties: JwtProperties
) {
    private val secretKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())
    }

    fun generateAccessToken(userUuid: String): String {
        return generateToken(userUuid, jwtProperties.accessTokenExpiration)
    }

    fun generateRefreshToken(userUuid: String): String {
        return generateToken(userUuid, jwtProperties.refreshTokenExpiration)
    }

    private fun generateToken(userUuid: String, expirationMillis: Long): String {
        val now = Date()
        val expiry = Date(now.time + expirationMillis)

        return Jwts.builder()
            .subject(userUuid)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(secretKey)
            .compact()
    }

    fun getUserUuid(token: String): String? {
        return try {
            val claims = parseClaims(token)
            claims?.subject
        } catch (e: Exception) {
            null
        }
    }

    fun validateToken(token: String): Boolean {
        return try {
            val claims = parseClaims(token)
            claims?.expiration?.after(Date()) ?: false
        } catch (e: ExpiredJwtException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun parseClaims(token: String): Claims? {
        return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload
    }
}