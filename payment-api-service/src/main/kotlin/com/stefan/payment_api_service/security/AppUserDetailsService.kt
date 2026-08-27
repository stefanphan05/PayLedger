package com.stefan.payment_api_service.security

import com.stefan.payment_api_service.repository.UserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class AppUserDetailsService(
    private val userRepository: UserRepository,
): UserDetailsService {
    override fun loadUserByUsername(email: String): UserDetails {
        val user = userRepository.findByEmail(email)
            .orElseThrow { UsernameNotFoundException("No user with email $email") }

        return UserSecurity(
            id = user.id,
            email = user.email,
            userPassword = user.password,
            userAuthorities = user.roles.map { SimpleGrantedAuthority("ROLE_${it.name}")
            }
        )
    }
}
