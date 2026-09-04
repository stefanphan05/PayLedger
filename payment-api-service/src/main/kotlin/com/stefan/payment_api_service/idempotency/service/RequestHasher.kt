package com.stefan.payment_api_service.idempotency.service

import com.stefan.payment_api_service.idempotency.model.IdempotentRequest
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class RequestHasher {
    fun hash(request: IdempotentRequest): String =
        MessageDigest.getInstance("SHA-256")
            .digest(request.canonicalForm().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}