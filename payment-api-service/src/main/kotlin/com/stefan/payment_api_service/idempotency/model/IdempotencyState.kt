package com.stefan.payment_api_service.idempotency.model

enum class IdempotencyState {
    /** Key claimed by SETNX; the handler has not started yet. */
    NEW,

    /** The handler is executing right now. */
    IN_PROGRESS,

    /** Finished successfully; the response is stored and replayable. */
    COMPLETED,

    /** Rejected by a deterministic client error; the error is replayable. */
    FAILED
}