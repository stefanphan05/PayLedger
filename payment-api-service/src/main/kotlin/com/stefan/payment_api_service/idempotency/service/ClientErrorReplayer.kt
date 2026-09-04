package com.stefan.payment_api_service.idempotency.service

import com.stefan.payment_api_service.exception.idempotency.IdempotencyReplayUnavailableException
import com.stefan.payment_api_service.exception.transaction.RecipientNotFoundException
import com.stefan.payment_api_service.exception.transaction.SelfTransferException
import org.springframework.stereotype.Component

@Component
class ClientErrorReplayer {

    /**
     * How to rebuild each replayable error from its stored message.
     *
     * Keyed off `simpleName` so it matches what `IdempotencyRecord.failed` writes.
     * `ClientErrorRegistryTests` asserts every ClientError appears here, so a new
     * one that nobody registers fails the build rather than surfacing in
     * production as an unreplayable key.
     */
    private val rebuildFunctions: Map<String, (message: String) -> RuntimeException> = mapOf(
        SelfTransferException::class.simpleName!! to { _ -> SelfTransferException() },
        RecipientNotFoundException::class.simpleName!! to { message -> RecipientNotFoundException.fromMessage(message) },
    )

    /** True if a stored failure of this type can be turned back into its original exception. */
    fun canReplay(errorType: String) = rebuildFunctions.containsKey(errorType)

    /**
     * Rebuilds and throws the original exception, so it reaches the same handler
     * that produced the first response and renders identically.
     *
     * An unrecognised type cannot be rendered faithfully. It reports that plainly
     * rather than reusing IdempotencyConflictException, whose "already in progress"
     * message would be untrue for a terminal record and would leave a polling
     * client waiting for a resolution that never comes.
     */
    fun rethrow(errorType: String, errorMessage: String): Nothing {
        val rebuild = rebuildFunctions[errorType]
            ?: throw IdempotencyReplayUnavailableException(errorType)

        throw rebuild(errorMessage)
    }
}
