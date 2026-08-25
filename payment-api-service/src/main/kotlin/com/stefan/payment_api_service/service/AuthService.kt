package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.exception.EmailAlreadyInUseException
import com.stefan.payment_api_service.models.dto.SignUpRequestDTO
import com.stefan.payment_api_service.models.entity.User
import com.stefan.payment_api_service.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {
    fun signUp(request: SignUpRequestDTO): User {
        val user = User(
            firstName = request.firstName,
            lastName = request.lastName,
            email = request.email.trim(),
            password = passwordEncoder.encode(request.password)!!
        )

        return try {
            userRepository.saveAndFlush(user)
        } catch (e: DataIntegrityViolationException) {
            throw EmailAlreadyInUseException(request.email)
        }
    }
}