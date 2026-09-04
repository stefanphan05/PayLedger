package com.stefan.payment_api_service.auth.controller

import com.stefan.payment_api_service.auth.model.AuthResponseDTO
import com.stefan.payment_api_service.auth.model.LoginRequestDTO
import com.stefan.payment_api_service.auth.model.MeResponseDTO
import com.stefan.payment_api_service.auth.model.SignUpRequestDTO
import com.stefan.payment_api_service.auth.model.UserResponseDTO
import com.stefan.payment_api_service.shared.security.UserSecurity
import com.stefan.payment_api_service.auth.service.AuthService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
) {
    @PostMapping("/signup")
    fun signup(@RequestBody @Valid signUpRequestDTO: SignUpRequestDTO): ResponseEntity<Any> {
        val user = authService.signUp(signUpRequestDTO)
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponseDTO.from(user))
    }

    @PostMapping("/login")
    fun login(@RequestBody @Valid loginRequestDTO: LoginRequestDTO): ResponseEntity<AuthResponseDTO> {
        return ResponseEntity.ok(authService.login(loginRequestDTO))
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: UserSecurity): ResponseEntity<MeResponseDTO> {
        val user = authService.getCurrentUser(principal.id)
        return ResponseEntity.ok(MeResponseDTO.from(user))
    }
}