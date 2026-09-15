package com.stefan.fraud_service.outbox.model

import com.stefan.fraud_service.consumer.model.PaymentEventPayload
import com.stefan.fraud_service.screening.model.TriggeredRule
import java.time.Instant
import java.util.UUID

data class FraudDecisionPayload (
    val action: String,
    val score: Int,
    val triggeredRules: List<TriggeredRule>,
    val overriddenBy: String? = null,
)

data class FraudEventEnvelope(
    val eventId: UUID,
    val eventType: FraudEventType,
    val occurredAt: Instant,
    val transactionId: UUID,
    val payload: PaymentEventPayload,

    /** New, and invisible to ledger-service: it ignores properties it does not know. */
    val decision: FraudDecisionPayload,
) {
    companion object {
        fun of(
            eventType: FraudEventType,
            payload: PaymentEventPayload,
            decision: FraudDecisionPayload,
        ) = FraudEventEnvelope(
            eventId = UUID.randomUUID(),
            eventType = eventType,
            occurredAt = Instant.now(),
            transactionId = payload.transactionId,
            payload = payload,
            decision = decision,
        )
    }
}