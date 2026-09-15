package com.stefan.fraud_service.consumer.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentEventPayload(
    val transactionId: UUID,
    val amount: BigDecimal,

    val currency: String,
    val senderId: UUID,
    val recipientId: UUID,
    val createdAt: Instant? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentEventEnvelope(
    val eventId: UUID,
    val eventType: String,
    val occurredAt: Instant,
    val transactionId: UUID,
    val payload: PaymentEventPayload,
)