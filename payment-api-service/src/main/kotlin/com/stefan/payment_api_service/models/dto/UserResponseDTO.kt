package com.stefan.payment_api_service.models.dto

import com.stefan.payment_api_service.models.entity.User
import java.time.Instant
import java.util.UUID

data class UserResponseDTO(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val email: String,
    val createdAt: Instant?,
) {
    companion object {
        fun from(user: User): UserResponseDTO {
            return UserResponseDTO(
                id = user.id,
                firstName = user.firstName,
                lastName = user.lastName,
                email = user.email,
                createdAt = user.createdAt,
            )
        }
    }
}