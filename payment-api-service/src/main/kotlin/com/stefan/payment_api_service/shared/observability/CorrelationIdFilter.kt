package com.stefan.payment_api_service.shared.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val correlationId = getCorrelationId(request)

        // Return the ID to the client.
        // This helps users include the correlation ID when reporting issues.
        response.setHeader(LogContext.HEADER, correlationId)

        // Store the ID in MDC so every log statement during this request
        // automatically includes the correlation ID.
        //
        // MDC is thread-local. The try/finally behavior from use{}
        // is important because application servers reuse threads.
        // Without removing it, the next request using the same thread
        // could accidentally inherit the previous request's ID.
        MDC.putCloseable(LogContext.CORRELATION_ID, correlationId).use {
            filterChain.doFilter(request, response)
        }
    }

    private fun getCorrelationId(request: HttpServletRequest): String {
        val existingId = request.getHeader(LogContext.HEADER)

        // Prefer the client-provided ID if it is valid.
        // This allows tracing a request across multiple services.
        return LogContext.sanitise(existingId)
            ?: UUID.randomUUID().toString()
    }
}