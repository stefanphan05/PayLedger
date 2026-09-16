package com.stefan.fraud_service.screening.model

import com.fasterxml.jackson.annotation.JsonFormat
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class DecisionResponseDTO(
    val transactionId: UUID,
    val senderId: UUID,
    val recipientId: UUID,

    // As a string, matching every other money field in this system.
    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,

    val currency: String,
    val screenedAt: Instant,
    val correlationId: String,
    val decision: FraudAction,
    val score: Int,
    val triggeredRules: List<TriggeredRule>,
    val overriddenAt: Instant?,
    val overriddenTo: FraudAction?,
) {
    companion object {
        fun from(attempt: PaymentAttempt, jsonMapper: JsonMapper): DecisionResponseDTO {
            val rules: List<TriggeredRule> = jsonMapper.readValue(
                attempt.triggeredRules,
                jsonMapper.typeFactory
                    .constructCollectionType(List::class.java, TriggeredRule::class.java),
            )

            return DecisionResponseDTO(
                transactionId = attempt.transactionId,
                senderId = attempt.senderId,
                recipientId = attempt.recipientId,
                amount = attempt.amount,
                currency = attempt.currency,
                screenedAt = attempt.screenedAt,
                // Handed back on purpose: it is the search key for asking
                // insights-service about this payment.
                correlationId = attempt.correlationId,
                decision = attempt.decision,
                score = attempt.score,
                triggeredRules = rules,
                overriddenAt = attempt.overriddenAt,
                overriddenTo = attempt.overriddenTo,
            )
        }
    }
}