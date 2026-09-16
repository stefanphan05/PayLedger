package com.stefan.fraud_service.shared.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity, jwtUtility: JwtUtility): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // Read-only and internal. Prometheus reaches these over the container
                // network; they are not published to the host.
                it.requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                    // Everything this service serves is an admin surface — there are no
                    // user-facing endpoints here at all — so this is one line rather
                    // than a @PreAuthorize on every method.
                    .requestMatchers("/fraud/**").hasRole("ADMIN")
                    .anyRequest().denyAll()
            }
            .addFilterBefore(
                JwtAuthenticationFilter(jwtUtility),
                UsernamePasswordAuthenticationFilter::class.java,
            )
            .build()
}