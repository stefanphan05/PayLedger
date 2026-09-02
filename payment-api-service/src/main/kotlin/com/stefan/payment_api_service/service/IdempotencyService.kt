package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.exception.ClientError
import com.stefan.payment_api_service.exception.IdempotencyConflictException
import com.stefan.payment_api_service.exception.IdempotencyKeyReuseException
import com.stefan.payment_api_service.models.dto.IdempotencyRecord
import com.stefan.payment_api_service.models.enum.IdempotencyState
import com.stefan.payment_api_service.repository.IdempotencyRepository
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
    }

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

        repository.markInProgress(userId, key, requestHash)

        val response = try {
            block()
        } catch (e: Exception) {
            onFailure(userId, key, requestHash, e)
            throw e
        }

        repository.complete(userId, key, IdempotencyRecord.completed(requestHash, response, jsonMapper))
        return response
    }

    /**
     * A deterministic rejection is part of the answer, so it is stored and replayed.
     * Anything else might succeed on the next attempt, so the key is handed back.
     */
    private fun onFailure(userId: UUID, key: String, requestHash: String, e: Exception) {
        if (e is ClientError) {
            repository.fail(userId, key, IdempotencyRecord.failed(requestHash, e))
        } else {
            repository.release(userId, key)
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
            IdempotencyState.FAILED -> replayer.rethrow(existing.errorType!!, existing.errorMessage!!)
            IdempotencyState.COMPLETED -> ResponseEntity.status(existing.responseStatus!!)
                .header(HEADER_IDEMPOTENT_REPLAY, "true")
                .body(jsonMapper.treeToValue(existing.responseBody, responseType))
        }
    }
}