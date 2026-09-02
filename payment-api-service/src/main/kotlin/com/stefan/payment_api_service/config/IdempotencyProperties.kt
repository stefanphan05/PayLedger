package com.stefan.payment_api_service.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "idempotency")
data class IdempotencyProperties(
    val ttl: Duration = Duration.ofHours(24)
)
