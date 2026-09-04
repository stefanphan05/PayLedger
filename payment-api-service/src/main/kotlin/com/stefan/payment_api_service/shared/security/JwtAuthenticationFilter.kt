package com.stefan.payment_api_service.shared.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val jwtUtility: JwtUtility,
    private val userDetailsService: UserDetailsService
): OncePerRequestFilter() {

    private var log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
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

        val token = header.substring(BEARER_PREFIX.length).trim()
        if (token.isEmpty()) return null
        return token
    }

    private fun authenticate(token: String, request: HttpServletRequest) {
        val email = jwtUtility.extractEmail(token) ?: return

        val userDetails = try {
            userDetailsService.loadUserByUsername(email)
        } catch (e: UsernameNotFoundException) {
            log.debug("Valid JWT for unknown user ${email}")
            return
        }

        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            userDetails,
            null,
            userDetails.authorities
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