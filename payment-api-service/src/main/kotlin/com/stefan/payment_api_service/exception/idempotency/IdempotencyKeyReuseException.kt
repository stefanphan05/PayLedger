package com.stefan.payment_api_service.exception.idempotency

class IdempotencyKeyReuseException : RuntimeException(
    "This Idempotency-Key was already used with a different request body"
)