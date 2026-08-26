package com.stefan.payment_api_service.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tools.jackson.databind.json.JsonMapper
import java.net.URI

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authenticationManager(configuration: AuthenticationConfiguration): AuthenticationManager = configuration.authenticationManager

    @Bean
    fun restAuthenticationEntryPoint(jsonMapper: JsonMapper): AuthenticationEntryPoint {
        return AuthenticationEntryPoint { request, response, _ ->
            val problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Authentication required"
            )

            problem.title = "Unauthorized"
            problem.instance = URI.create(request.requestURI)

            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            jsonMapper.writeValue(response.writer, problem)
        }
    }

    @Bean
    fun filterChain(
        http: HttpSecurity,
        jwtUtility: JwtUtility,
        appUserDetailsService: AppUserDetailsService,
        authenticationEntryPoint: AuthenticationEntryPoint
    ): SecurityFilterChain {
        val jwtAuthenticationFilter = JwtAuthenticationFilter(jwtUtility, appUserDetailsService)

        return http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/auth/signup", "/auth/login").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { it.authenticationEntryPoint(authenticationEntryPoint) }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
    }
}