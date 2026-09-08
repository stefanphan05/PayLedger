package com.stefan.ledger_service.outbox.model

import com.stefan.ledger_service.ledger.model.RejectionReason
import java.time.Instant
import java.util.UUID

/**
 * What the ledger decided. Small on purpose: the consumer already knows the payment,
 * it only needs the final decision and, when refused, why.
 */
data class LedgerEventPayload(
    val transactionId: UUID,
    val reason: RejectionReason?
)

data class LedgerEventEnvelope(
    val eventId: UUID,
    val eventType: LedgerEventType,
    val occurredAt: Instant,
    val transactionId: UUID,
    val payload: LedgerEventPayload
) {
    companion object {
        fun of(
            eventType: LedgerEventType,
            transactionId: UUID,
            reason: RejectionReason? = null,
        ) = LedgerEventEnvelope(
            eventId = UUID.randomUUID(),
            eventType = eventType,
            occurredAt = Instant.now(),
            transactionId = transactionId,
            payload = LedgerEventPayload(transactionId, reason)
        )
    }
}
