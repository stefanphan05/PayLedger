package com.stefan.payment_api_service.exception.idempotency

class IdempotencyReplayUnavailableException(errorType: String) : RuntimeException(
    "The original outcome for this Idempotency-Key cannot be replayed " +
        "(unrecognised error type '$errorType'). Retry with a new key."
)
