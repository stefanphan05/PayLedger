package com.stefan.payment_api_service.shared.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Component
class JwtUtility(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.expiration-ms}") val expirationMs: Long
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val key: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))

    fun generateToken(userId: UUID, email: String): String {
        val now = Date()
        val expiration = Date(now.time + expirationMs)

        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(now)
            .expiration(expiration)
            .signWith(key)
            .compact()
    }

    fun extractUserId(token: String): UUID? {
        val subject = getClaims(token)?.subject ?: return null

        return try {
            UUID.fromString(subject)
        } catch (e: IllegalArgumentException) {
            logger.debug("Token subject is not a valid UUID")
            null
        }
    }

    fun extractEmail(token: String): String? = getClaims(token)?.get("email", String::class.java)

    fun isTokenValid(token: String): Boolean = getClaims(token) != null

    private fun getClaims(token: String): Claims? {
        return try {
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (e: JwtException) {
            logger.debug("Rejected JWT: {}", e.message)
            null
        } catch (e: IllegalArgumentException) {
            logger.debug("Rejected JWT: {}", e.message)
            null
        }
    }
}
