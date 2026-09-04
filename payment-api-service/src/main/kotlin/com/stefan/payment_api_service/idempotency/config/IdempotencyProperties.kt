package com.stefan.payment_api_service.idempotency.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "idempotency")
data class IdempotencyProperties(
    /** How long a completed or failed key is replayable. */
    val ttl: Duration = Duration.ofHours(24),

    /**
     * How long a key is held after an ambiguous failure - one where the database
     * write may or may not have committed. Retries are refused for this long, then
     * allowed through. Short enough not to strand a payment, long enough to cover
     * an in-flight commit.
     */
    val ambiguousFailureHold: Duration = Duration.ofSeconds(60),
)
