package com.stefan.payment_api_service.exception

class EmailAlreadyInUseException(email: String) : RuntimeException("Email $email is already registered") {
}