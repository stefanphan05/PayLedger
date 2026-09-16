package com.stefan.payment_api_service.shared.security

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

    @Test
    fun `generateToken carries the roles it was given`() {
        val token = jwtUtil.generateToken(
            userId = UUID.randomUUID(),
            email = "admin@example.com",
            roles = setOf("USER", "ADMIN")
        )

        assertEquals(setOf("USER", "ADMIN"), jwtUtil.extractRoles(token))
    }

    @Test
    fun `role are written without Spring's ROLE_ prefix`() {
        val token = jwtUtil.generateToken(
            userId = UUID.randomUUID(),
            email = "admin@example.com",
            roles = setOf("ADMIN"),
        )

        // The prefix is a framework convention. A service reading this token adds its
        // own, so putting one in here would leak Spring into the wire contract.
        assertEquals(setOf("ADMIN"), jwtUtil.extractRoles(token))
    }

    @Test
    fun `a token issued before the roles claim existed grants no roles`() {
        val token = jwtUtil.generateToken(userId = UUID.randomUUID(), email = "test@example.com")

        assertTrue(jwtUtil.isTokenValid(token))
        assertTrue(jwtUtil.extractRoles(token).isEmpty())
    }

    @Test
    fun `extractRoles grants nothing for a token signed with another key`() {
        val otherUtil = JwtUtility(
            secret = Encoders.BASE64.encode(ByteArray(32) { 2 }),
            expirationMs = 3_600_000,
        )

        // The signature is checked before the claim is read. A forged admin token must
        // come back empty, not admin.
        val forged = otherUtil.generateToken(
            userId = UUID.randomUUID(),
            email = "attacker@example.com",
            roles = setOf("ADMIN"),
        )

        assertTrue(jwtUtil.extractRoles(forged).isEmpty())
    }
}
