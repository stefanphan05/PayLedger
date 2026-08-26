package com.stefan.payment_api_service.models.dto

import com.stefan.payment_api_service.models.entity.User
import com.stefan.payment_api_service.models.enum.Role
import java.time.Instant
import java.util.UUID

data class MeResponseDTO(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val email: String,
    val roles: Set<Role>,
    val createdAt: Instant?,
) {
    companion object {
        fun from(user: User): MeResponseDTO {
            return MeResponseDTO(
                id = user.id,
                firstName = user.firstName,
                lastName = user.lastName,
                email = user.email,
                roles = user.roles.toSet(),
                createdAt = user.createdAt,
            )
        }
    }
}
