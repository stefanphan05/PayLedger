package com.stefan.payment_api_service.auth.model

import com.stefan.payment_api_service.auth.model.User
import com.stefan.payment_api_service.auth.model.Role
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class SignUpRequestDTO(
    @field:NotBlank(message = "First name is required")
    @field:Size(max = 50, message = "First name must be at most 50 characters")
    val firstName: String,

    @field:NotBlank(message = "Last name is required")
    @field:Size(max = 50, message = "Last name must be at most 50 characters")
    val lastName: String,

    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Email must be a valid address")
    @field:Size(max = 255, message = "Email must be at most 255 characters")
    val email: String,

    @field:NotBlank(message = "Password is required")
    @field:Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    val password: String,
)

data class LoginRequestDTO(
    @field:NotBlank(message = "Email is required")
    val email: String,

    @field:NotBlank(message = "Password is required")
    val password: String,
)

data class AuthResponseDTO(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
)

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
