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
        val user = mockUser()

        val token = jwtUtil.generateToken(user)

        assertEquals(user.email, jwtUtil.extractEmail(token))
        assertEquals(user.id, jwtUtil.extractUserId(token))
        assertTrue(jwtUtil.isValid(token))
    }

    @Test
    fun `isValid rejects token signed with another key`() {
        val otherUtil = JwtUtility(
            secret = Encoders.BASE64.encode(ByteArray(32) { 2 }),
            expirationMs = 3_600_000,
        )

        assertFalse(jwtUtil.isValid(otherUtil.generateToken(mockUser())))
    }

    @Test
    fun `isValid rejects expired token`() {
        val expiredUtil = JwtUtility(secret = secret, expirationMs = -120_000)

        assertFalse(jwtUtil.isValid(expiredUtil.generateToken(mockUser())))
    }


    private fun mockUser(): UserSecurity {
        return UserSecurity(
            id = UUID.randomUUID(),
            email = "test@example.com",
            userPassword = "hashedPassword",
            userAuthorities = emptyList(),
        )
    }
}