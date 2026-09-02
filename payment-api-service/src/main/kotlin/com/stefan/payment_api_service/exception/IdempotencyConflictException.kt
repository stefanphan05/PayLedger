package com.stefan.payment_api_service.exception

class IdempotencyConflictException : RuntimeException(
    "A request with this Idempotency-Key is already in progress"
)