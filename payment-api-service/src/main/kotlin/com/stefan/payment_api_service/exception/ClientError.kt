package com.stefan.payment_api_service.exception

/**
 * Marks an exception as a deterministic rejection of the request itself:
 * the same request will always be rejected the same way. Safe to store as
 * FAILED against an idempotency key and replay.
 *
 * Do NOT mark transient or infrastructure failures — those must stay
 * retryable with the same key.
 */
interface ClientError