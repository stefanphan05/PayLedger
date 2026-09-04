package com.stefan.payment_api_service.auth.service

import com.stefan.payment_api_service.exception.auth.EmailAlreadyInUseException
import com.stefan.payment_api_service.auth.model.AuthResponseDTO
import com.stefan.payment_api_service.auth.model.LoginRequestDTO
import com.stefan.payment_api_service.auth.model.SignUpRequestDTO
import com.stefan.payment_api_service.auth.model.User
import com.stefan.payment_api_service.auth.repository.UserRepository
import com.stefan.payment_api_service.shared.security.JwtUtility
import com.stefan.payment_api_service.shared.security.UserSecurity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authenticationManager: AuthenticationManager,
    private val jwtUtility: JwtUtility,
) {
    fun signUp(request: SignUpRequestDTO): User {
        val user = User(
            firstName = request.firstName,
            lastName = request.lastName,
            email = request.email.trim().lowercase(),
            password = passwordEncoder.encode(request.password)!!
        )

        return try {
            userRepository.saveAndFlush(user)
        } catch (e: DataIntegrityViolationException) {
            throw EmailAlreadyInUseException(request.email)
        }
    }

    fun getCurrentUser(userId: UUID): User {
        return userRepository.findById(userId)
            .orElseThrow { UsernameNotFoundException("No user with id $userId") }
    }

    fun login(request: LoginRequestDTO): AuthResponseDTO {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(
                request.email.trim().lowercase(),
                request.password
            )
        )

        val principle = authentication.principal as UserSecurity

        return AuthResponseDTO(
            token = jwtUtility.generateToken(userId = principle.id, email = principle.email),
            expiresIn = jwtUtility.expirationMs / 1000
        )
    }
}