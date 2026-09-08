package com.stefan.ledger_service.ledger.consumer.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * This service's own copy of the payload published by payment-api-service, not a shared module
 * The contract between the service is the JSON, not a Kotlin class
 *
 * status is absent here because the ledger decides a payment's outcome so it doesn't trust
 * the status the API sent
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentEventPayload (
    val transactionId: UUID,

    // Arrives as the JSON string "1000.25" not a number. The producer serializes
    // it that way on purpose: money read into a double is how cents go missing
    val amount: BigDecimal,

    val currency: String,
    val senderId: UUID,
    val recipientId: UUID
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentEventEnvelope(
    val eventId: UUID,
    val eventType: String,

    val occurredAt: Instant,
    val transactionId: UUID,
    val payload: PaymentEventPayload,
)