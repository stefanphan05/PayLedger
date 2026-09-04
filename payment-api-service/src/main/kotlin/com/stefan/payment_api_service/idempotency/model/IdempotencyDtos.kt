package com.stefan.payment_api_service.idempotency.model

import com.stefan.payment_api_service.exception.idempotency.InvalidIdempotencyKeyException
import com.stefan.payment_api_service.idempotency.model.IdempotencyState
import org.springframework.http.ResponseEntity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

data class IdempotencyRecord(
    val state: IdempotencyState,
    val requestHash: String,

    // COMPLETED only
    val responseStatus: Int? = null,
    val responseBody: JsonNode? = null,

    // FAILED only
    val errorType: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun completed(requestHash: String, response: ResponseEntity<*>, jsonMapper: JsonMapper): IdempotencyRecord =
            IdempotencyRecord(
                state = IdempotencyState.COMPLETED,
                requestHash = requestHash,
                responseStatus = response.statusCode.value(),
                responseBody = jsonMapper.valueToTree(response.body),
            )

        fun failed(requestHash: String, e: Exception): IdempotencyRecord =
            IdempotencyRecord(
                state = IdempotencyState.FAILED,
                requestHash = requestHash,
                errorType = e::class.simpleName ?: e::class.java.name,
                errorMessage = e.message,
            )
    }
}

/**
 * Lets a request DTO describe the exact fields that make it "the same request"
 * as another one — so two calls with the same intent (but maybe slightly
 * different formatting, like "50.00" vs "50.0") are recognised as identical.
 *
 * Used to check whether a retried request matches the original request behind
 * an Idempotency-Key, so we can safely tell "same request repeated" apart
 * from "different request accidentally reusing the same key".
 */
interface IdempotentRequest {
    fun canonicalForm(): String
}

class IdempotencyKeyDTO(val value: String) {
    init {
        // Not `require`: an IllegalArgumentException falls through to the catch-all
        // handler as a 500, which would blame the server for a client's bad header.
        if (value.isBlank()) {
            throw InvalidIdempotencyKeyException("Idempotency-Key must not be blank")
        }
        if (value.length !in MIN_LENGTH..MAX_LENGTH) {
            throw InvalidIdempotencyKeyException(
                "Idempotency-Key must be between $MIN_LENGTH and $MAX_LENGTH characters"
            )
        }
        if (!ALLOWED_CHARACTERS.matches(value)) {
            throw InvalidIdempotencyKeyException(
                "Idempotency-Key may only contain letters, digits, hyphens and underscores"
            )
        }
    }

    companion object {
        private const val MIN_LENGTH = 8
        private const val MAX_LENGTH = 255
        private val ALLOWED_CHARACTERS = Regex("^[A-Za-z0-9_-]+$")
    }
}