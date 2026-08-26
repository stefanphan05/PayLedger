package com.stefan.payment_api_service.controller

import com.stefan.payment_api_service.models.dto.AuthResponseDTO
import com.stefan.payment_api_service.models.dto.LoginRequestDTO
import com.stefan.payment_api_service.models.dto.SignUpRequestDTO
import com.stefan.payment_api_service.models.dto.UserResponseDTO
import com.stefan.payment_api_service.service.AuthService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
}