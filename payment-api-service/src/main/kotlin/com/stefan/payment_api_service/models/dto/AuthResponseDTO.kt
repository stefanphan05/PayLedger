package com.stefan.payment_api_service.models.dto

data class AuthResponseDTO(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
)