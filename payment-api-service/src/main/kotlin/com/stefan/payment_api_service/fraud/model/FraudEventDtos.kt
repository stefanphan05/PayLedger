package com.stefan.payment_api_service.fraud.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class FraudEventEnvelope(
    val eventId: UUID,
    val eventType: String,
    val occurredAt: Instant,
    val transactionId: UUID,
)