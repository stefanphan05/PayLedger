package com.stefan.payment_api_service.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.util.UUID

class UserSecurity(
    val id: UUID,
    val email: String,
    private val userPassword: String,
    private val userAuthorities: MutableCollection<GrantedAuthority>
): UserDetails {
    override fun getAuthorities() = userAuthorities
    override fun getPassword() = userPassword
    override fun getUsername() = email
    override fun isAccountNonExpired() = true
    override fun isAccountNonLocked() = true
    override fun isCredentialsNonExpired() = true
    override fun isEnabled() = true
}