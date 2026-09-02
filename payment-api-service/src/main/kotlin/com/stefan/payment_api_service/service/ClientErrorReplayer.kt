package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.exception.IdempotencyConflictException
import com.stefan.payment_api_service.exception.RecipientNotFoundException
import com.stefan.payment_api_service.exception.SelfTransferException
import org.springframework.stereotype.Component

@Component
class ClientErrorReplayer {

    // A lookup table: "if the stored error type is X, rebuild it using this function"
    private val rebuildFunctions: Map<String, (message: String) -> RuntimeException> = mapOf(
        "SelfTransferException" to { _ -> SelfTransferException() },
        "RecipientNotFoundException" to { message -> RecipientNotFoundException.fromMessage(message) },
    )

    /** Rebuilds and throws the original exception, so it hits the same handler as before. */
    fun rethrow(errorType: String, errorMessage: String): Nothing {
        val rebuild =
            rebuildFunctions[errorType] ?: throw IdempotencyConflictException()

        throw rebuild(errorMessage)
    }
}