package com.stefan.payment_api_service.outbox.model

import com.fasterxml.jackson.annotation.JsonFormat
import com.stefan.payment_api_service.transaction.model.Transaction
import com.stefan.payment_api_service.outbox.model.PaymentEventType
import com.stefan.payment_api_service.transaction.model.TransactionStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The transaction as saved. A full snapshot rather than just an id, so a consumer never
 * has to call back into this service - the coupling ADR-0003 set out to remove.
 */
data class PaymentEventPayload(
    val transactionId: UUID,

    // As a string, matching TransactionResponseDTO. Money in a JSON float is a real bug
    // class: a JS consumer reading 125.50 as a double is how cents go missing.
    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,

    val currency: String,
    val status: TransactionStatus,
    val senderId: UUID,
    val recipientId: UUID,
    val createdAt: Instant?,
) {
    companion object {
        fun from(transaction: Transaction) = PaymentEventPayload(
            transactionId = transaction.id,
            amount = transaction.amount,
            currency = transaction.currency,
            status = transaction.transactionStatus,
            senderId = transaction.senderId,
            recipientId = transaction.recipientId,
            createdAt = transaction.createdAt,
        )
    }
}

/** What actually goes on the topic. */
data class PaymentEventEnvelope(
    /** Unique per published event. Consumers dedupe on this - a producer-side retry
     *  after a network timeout can put the same event on the topic twice. */
    val eventId: UUID,
    val eventType: PaymentEventType,   // Jackson writes the constant name
    val occurredAt: Instant,
    val transactionId: UUID,
    val payload: PaymentEventPayload,
) {
    companion object {
        fun of(eventType: PaymentEventType, transaction: Transaction) = PaymentEventEnvelope(
            eventId = UUID.randomUUID(),
            eventType = eventType,
            occurredAt = Instant.now(),
            transactionId = transaction.id,
            payload = PaymentEventPayload.from(transaction),
        )
    }
}