package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.exception.ClientError
import com.stefan.payment_api_service.exception.IdempotencyConflictException
import com.stefan.payment_api_service.exception.IdempotencyKeyReuseException
import com.stefan.payment_api_service.models.dto.IdempotencyRecord
import com.stefan.payment_api_service.models.enum.IdempotencyState
import com.stefan.payment_api_service.repository.IdempotencyRepository
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Service
class IdempotencyService(
    private val repository: IdempotencyRepository,
    private val replayer: ClientErrorReplayer,
    private val jsonMapper: JsonMapper,
) {
    companion object {
        const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
        const val HEADER_IDEMPOTENT_REPLAY = "Idempotent-Replay"
        private const val UNKNOWN_ERROR_TYPE = "UnknownClientError"
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    fun <T : Any> execute(
        userId: UUID,
        key: String,
        requestHash: String,
        responseType: Class<T>,
        block: () -> ResponseEntity<T>
    ): ResponseEntity<T> {
        if (!repository.reserve(userId, key, requestHash)) {
            return replay(userId, key, requestHash, responseType)
        }

        try {
            repository.markInProgress(userId, key, requestHash)
        } catch (e: Exception) {
            // Nothing has been attempted yet, so the key is safe to hand straight
            // back. Leaving it claimed would block this payment for the full TTL
            // over a purely diagnostic write.
            releaseQuietly(userId, key)
            throw e
        }

        val response = try {
            block()
        } catch (e: Exception) {
            try {
                onFailure(userId, key, requestHash, e)
            } catch (bookkeeping: Exception) {
                // The caller's error is the real answer. Never let a failed cleanup
                // write replace a 400 with a generic 500.
                logger.warn("Idempotency bookkeeping failed for key {}", key, bookkeeping)
                e.addSuppressed(bookkeeping)
            }
            throw e
        }

        try {
            repository.complete(userId, key, IdempotencyRecord.completed(requestHash, response, jsonMapper))
        } catch (e: Exception) {
            // The payment committed. Reporting a 500 here would tell the client it
            // failed and send them into a retry, so return the response anyway and
            // let the key expire holding IN_PROGRESS.
            logger.error("Transaction committed but its idempotency record was not stored, key {}", key, e)
        }
        return response
    }

    /**
     * A deterministic rejection is part of the answer, so it is stored and replayed.
     *
     * Anything else is ambiguous: a connection drop at commit time can surface as an
     * exception even though the commit landed, so an exception here is not proof that
     * nothing was written. Releasing the key there would let a retry create a second payment —
     * the exact failure this class exists to prevent — so the key is kept and its
     * life shortened instead. Retries are refused only while the outcome is
     * genuinely unknown, then the client is let through.
     */
    private fun onFailure(userId: UUID, key: String, requestHash: String, e: Exception) {
        if (e is ClientError) {
            repository.fail(userId, key, IdempotencyRecord.failed(requestHash, e))
        } else {
            repository.holdBriefly(userId, key)
        }
    }

    private fun releaseQuietly(userId: UUID, key: String) {
        try {
            repository.release(userId, key)
        } catch (e: Exception) {
            logger.warn("Could not release idempotency key {}", key, e)
        }
    }

    private fun <T : Any> replay(
        userId: UUID,
        key: String,
        requestHash: String,
        responseType: Class<T>,
    ): ResponseEntity<T> {
        val existing = repository.find(userId, key)
            ?: throw IdempotencyConflictException()   // expired between SETNX and GET

        if (existing.requestHash != requestHash) throw IdempotencyKeyReuseException()

        return when (existing.state) {
            IdempotencyState.NEW, IdempotencyState.IN_PROGRESS -> throw IdempotencyConflictException()

            // Defaults rather than !!: a ClientError with no message would otherwise
            // succeed on the first attempt and then NPE on every retry until the TTL.
            IdempotencyState.FAILED -> replayer.rethrow(
                existing.errorType ?: UNKNOWN_ERROR_TYPE,
                existing.errorMessage.orEmpty(),
            )

            IdempotencyState.COMPLETED -> ResponseEntity.status(existing.responseStatus!!)
                .header(HEADER_IDEMPOTENT_REPLAY, "true")
                .body(jsonMapper.treeToValue(existing.responseBody, responseType))
        }
    }
}
