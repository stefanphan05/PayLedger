package com.stefan.payment_api_service.exception.auth

class EmailAlreadyInUseException(email: String) : RuntimeException("Email $email is already registered") {
}