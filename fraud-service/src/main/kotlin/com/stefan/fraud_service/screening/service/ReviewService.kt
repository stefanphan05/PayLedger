package com.stefan.fraud_service.screening.service

import com.stefan.fraud_service.consumer.model.PaymentEventPayload
import com.stefan.fraud_service.exception.ReviewConflictException
import com.stefan.fraud_service.exception.DecisionNotFoundException
import com.stefan.fraud_service.outbox.model.FraudDecisionPayload
import com.stefan.fraud_service.outbox.model.FraudEventType
import com.stefan.fraud_service.outbox.service.FraudEventPublisher
import com.stefan.fraud_service.screening.model.FraudAction
import com.stefan.fraud_service.screening.model.PaymentAttempt
import com.stefan.fraud_service.screening.model.TriggeredRule
import com.stefan.fraud_service.screening.repository.PaymentAttemptRepository
import com.stefan.fraud_service.shared.observability.LogContext
import org.springframework.transaction.annotation.Transactional
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * An admin overturning the rules on one held payment
 */
@Service
class ReviewService(
    private val attempts: PaymentAttemptRepository,
    private val fraudEvents: FraudEventPublisher,
    private val jsonMapper: JsonMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun release(transactionId: UUID, actor: String): PaymentAttempt =
        override(transactionId, FraudAction.ALLOW, FraudEventType.PAYMENT_CLEARED, actor)

    @Transactional
    fun reject(transactionId: UUID, actor: String): PaymentAttempt =
        override(transactionId, FraudAction.BLOCK, FraudEventType.PAYMENT_BLOCKED, actor)

    private fun override(
        transactionId: UUID,
        to: FraudAction,
        eventType: FraudEventType,
        actor: String
    ): PaymentAttempt {
        val attempt = attempts.findById(transactionId).orElseThrow { DecisionNotFoundException(transactionId) }

        if (attempt.decision != FraudAction.REVIEW) {
            throw ReviewConflictException(transactionId, attempt.decision.name)
        }

        if (attempt.overriddenAt != null) {
            throw ReviewConflictException(transactionId, "already overridden")
        }

        return MDC.putCloseable(LogContext.CORRELATION_ID, attempt.correlationId).use {
            MDC.putCloseable(LogContext.TRANSACTION_ID, transactionId.toString()).use {
                attempt.overriddenAt = Instant.now()
                attempt.overriddenTo = to

                fraudEvents.publish(
                    eventType = eventType,
                    payload = payloadOf(attempt),
                    decision = FraudDecisionPayload(
                        action = to.name,
                        score = attempt.score,
                        triggeredRules = rulesOf(attempt),
                        overriddenBy = actor,
                    ),
                )

                val saved = attempts.save(attempt)

                // Reads into the corpus as the end of the story: "...held for review...
                // Review overridden to ALLOW by <admin>". Short, for the same word-budget
                // reason as the screening line.
                logger.info("Review overridden to {} by {}", to, actor)
                saved
            }
        }
    }

    private fun payloadOf(attempt: PaymentAttempt) = PaymentEventPayload(
        transactionId = attempt.transactionId,
        amount = attempt.amount,
        currency = attempt.currency,
        senderId = attempt.senderId,
        recipientId = attempt.recipientId,
    )

    private fun rulesOf(attempt: PaymentAttempt): List<TriggeredRule> =
        jsonMapper.readValue(
            attempt.triggeredRules,
            jsonMapper.typeFactory
                .constructCollectionType(List::class.java, TriggeredRule::class.java),
        )
}