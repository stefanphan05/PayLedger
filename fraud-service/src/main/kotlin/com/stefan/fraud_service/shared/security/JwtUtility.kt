package com.stefan.fraud_service.shared.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Verifies tokens minted by payment-api, using the same shared secret.
 *
 * Read-only by design: no users table, nothing issued. It trusts the signature and the
 * roles claim inside it, which is what that claim was added for — a service with no
 * users table still has to decide whether a caller is an admin, and calling payment-api
 * to ask would put back exactly the coupling the gate design removed.
 */
@Component
class JwtUtility(@Value("\${jwt.secret}") secret: String) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val key: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))

    fun extractUserId(token: String): UUID? {
        val subject = getClaims(token)?.subject ?: return null

        return try {
            UUID.fromString(subject)
        } catch (e: IllegalArgumentException) {
            logger.debug("Token subject is not a valid UUID")
            null
        }
    }

    fun extractRoles(token: String): Set<String> {
        val claim = getClaims(token)?.get(ROLES_CLAIM) as? List<*> ?: return emptySet()
        return claim.filterIsInstance<String>().toSet()
    }

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

    private companion object {
        const val ROLES_CLAIM = "roles"
    }
}