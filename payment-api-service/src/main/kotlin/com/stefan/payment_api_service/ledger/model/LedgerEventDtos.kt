package com.stefan.payment_api_service.ledger.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class LedgerEventPayload(
    val transactionId: UUID,
    val reason: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LedgerEventEnvelope(
    val eventId: UUID,
    val eventType: String,
    val occurredAt: Instant,
    val transactionId: UUID,
    val payload: LedgerEventPayload
)
