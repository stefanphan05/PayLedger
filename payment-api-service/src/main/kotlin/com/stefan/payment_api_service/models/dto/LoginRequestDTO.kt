package com.stefan.payment_api_service.models.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class LoginRequestDTO(
    @field:NotBlank(message = "Email is required")
    val email: String,

    @field:NotBlank(message = "Password is required")
    @field:Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    val password: String,
)
