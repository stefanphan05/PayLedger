package com.stefan.payment_api_service.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Component
class JwtUtility(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.expiration-ms}") private val expirationMs: Long
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))

    fun generateToken(user: UserSecurity): String {
        val now = Date()
        return Jwts.builder()
            .subject(user.email)
            .claim("userId", user.id.toString())
            .issuedAt(now)
            .expiration(Date(now.time + expirationMs))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    fun extractEmail(token: String): String {
        return parseClaims(token).subject
    }

    fun extractUserId(token: String): UUID {
        return UUID.fromString(parseClaims(token).get("userId", String::class.java))
    }

    fun isValid(token: String): Boolean {
        return try {
            parseClaims(token)
            true
        } catch (e: JwtException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun parseClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(key)
            .clockSkewSeconds(60)
            .build()
            .parseSignedClaims(token)
            .payload
    }
}