package com.stefan.payment_api_service.security

import io.jsonwebtoken.io.Encoders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse

class JwtUtilityTests {
    private val secret = Encoders.BASE64.encode(ByteArray(32) { 1 })
    private val jwtUtil = JwtUtility(secret = secret, expirationMs = 3_600_000)

    @Test
    fun `generateToken return email and userId`() {
        val userId = UUID.randomUUID()
        val email = "test@example.com"

        val token = jwtUtil.generateToken(
            userId = userId,
            email = email
        )

        assertEquals(email, jwtUtil.extractEmail(token))
        assertEquals(userId, jwtUtil.extractUserId(token))
        assertTrue(jwtUtil.isTokenValid(token))
    }

    @Test
    fun `isTokenValid rejects token signed with another key`() {
        val otherUtil = JwtUtility(
            secret = Encoders.BASE64.encode(ByteArray(32) { 2 }),
            expirationMs = 3_600_000,
        )

        assertFalse(jwtUtil.isTokenValid(otherUtil.generateToken(
            userId = UUID.randomUUID(),
            email = "test@example.com"
        )))
    }

    @Test
    fun `isTokenValid rejects expired token`() {
        val expiredUtil = JwtUtility(secret = secret, expirationMs = -120_000)

        assertFalse(jwtUtil.isTokenValid(expiredUtil.generateToken(
            userId = UUID.randomUUID(),
            email = "test@example.com"
        )))
    }
}
