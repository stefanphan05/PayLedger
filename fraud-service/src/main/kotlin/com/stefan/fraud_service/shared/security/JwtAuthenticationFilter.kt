package com.stefan.fraud_service.shared.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Builds an Authentication straight from the token's claims.
 *
 * The difference from payment-api's version, and the whole point: no UserDetailsService
 * lookup, because there is no users table here to look anything up in. The signature
 * proves the token came from payment-api; the roles claim says what the caller may do.
 *
 * The trade this accepts: a role revoked in payment-api stays valid here until the
 * token expires. Fine at a one-hour expiry for an internal admin surface; it would not
 * be for anything issuing long-lived tokens.
 */
class JwtAuthenticationFilter(
    private val jwtUtility: JwtUtility,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = resolveToken(request)

        if (token != null && SecurityContextHolder.getContext().authentication == null) {
            authenticate(token, request)
        }

        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null

        return header.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
    }

    private fun authenticate(token: String, request: HttpServletRequest) {
        val userId = jwtUtility.extractUserId(token) ?: return
        // The claim travels unprefixed - payment-api's UserSecurity.roleName() strips
        // ROLE_ before minting the token. The prefix is a Spring convention, not part of
        // the wire format, so it goes back on here. Without it hasRole("ADMIN") looks for
        // ROLE_ADMIN, finds ADMIN, and every admin call 403s with a perfectly valid token.
        val authorities = jwtUtility.extractRoles(token).map { SimpleGrantedAuthority("ROLE_$it") }

        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            userId,
            null,
            authorities,
        )
        authentication.details = WebAuthenticationDetailsSource().buildDetails(request)

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}