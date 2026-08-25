package com.stefan.payment_api_service.models.dto

import jakarta.validation.constraints.NotBlank

data class LoginRequestDTO(
    @field:NotBlank(message = "Email is required")
    val email: String,

    @field:NotBlank(message = "Password is required")
    val password: String,
)
