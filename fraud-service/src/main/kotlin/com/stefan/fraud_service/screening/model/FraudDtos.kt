package com.stefan.fraud_service.screening.model

import com.stefan.fraud_service.consumer.model.PaymentEventPayload
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** The payment being judged, flattened out of the inbound event. */
data class ScreenCandidate(
    val transactionId: UUID,
    val senderId: UUID,
    val recipientId: UUID?,
    val amount: BigDecimal,
    val currency: String,
    val screenedAt: Instant,
) {
    companion object {
        fun of(payload: PaymentEventPayload, screenedAt: Instant) = ScreenCandidate(
            transactionId = payload.transactionId,
            senderId = requireNotNull(payload.senderId) { "a screened payment always has a sender" },
            recipientId = payload.recipientId,
            amount = payload.amount,
            currency = payload.currency,
            screenedAt = screenedAt,
        )
    }
}

/**
 * Everything the five rules need about this sender, from one query
 */
data class SenderHistory(
    /** Payments from this sender inside the velocity window. */
    val recentCount: Long,
    /** Distinct recipients this sender has paid inside the fan-out window. */
    val distinctRecipients: Long,
    /** Times this sender has paid THIS recipient before. Zero means new. */
    val priorToRecipient: Long,
    /** Payments inside the baseline window, and their mean. Used by AMOUNT_ANOMALY. */
    val baselineCount: Long,
    val baselineMean: BigDecimal,
)

/** One rule that fired, and enough detail to explain it to a human without re-deriving it. */
data class TriggeredRule(
    val rule: String,
    val weight: Int,
    val detail: String,
)

/** What screening concluded. */
data class Decision(
    val action: FraudAction,
    val score: Int,
    val triggeredRules: List<TriggeredRule>,
)
