package com.stefan.fraud_service.screening.service

import com.stefan.fraud_service.config.FraudProperties
import com.stefan.fraud_service.consumer.model.PaymentEventEnvelope
import com.stefan.fraud_service.consumer.model.PaymentEventPayload
import com.stefan.fraud_service.outbox.model.FraudDecisionPayload
import com.stefan.fraud_service.outbox.model.FraudEventType
import com.stefan.fraud_service.outbox.service.FraudEventPublisher
import com.stefan.fraud_service.screening.model.Decision
import com.stefan.fraud_service.screening.model.FraudAction
import com.stefan.fraud_service.screening.model.PaymentAttempt
import com.stefan.fraud_service.screening.model.ScreenCandidate
import com.stefan.fraud_service.screening.model.SenderHistory
import com.stefan.fraud_service.screening.repository.PaymentAttemptRepository
import com.stefan.fraud_service.screening.repository.ProcessedEventRepository
import com.stefan.fraud_service.screening.rules.FraudRule
import com.stefan.fraud_service.shared.observability.LogContext
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

@Service
class ScreeningService(
    private val rules: List<FraudRule>,
    private val attempts: PaymentAttemptRepository,
    private val processedEvent: ProcessedEventRepository,
    private val fraudEvents: FraudEventPublisher,
    private val properties: FraudProperties,
    private val jsonMapper: JsonMapper,
    private val entityManager: EntityManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Everything for one payment in ONE transaction: the dedupe claim, the decision,
     * and the event announcing it. All of it commits, or none of it does
     */
    @Transactional
    fun screen(envelope: PaymentEventEnvelope): Decision {
        processedEvent.record(
            envelope.eventId,
            envelope.transactionId,
        )

        val payload = envelope.payload

        if (payload.type == PaymentEventPayload.DEPOSIT) {
            return clearWithoutScreening(payload)
        }

        val candidate = ScreenCandidate.of(payload, Instant.now())
        val history = historyFor(candidate)
        val decision = decide(candidate, history)

        recordAttempt(candidate, decision)
        publish(payload, decision)

        logger.info(
            "Screened as {}, score {}/100, rules: {}",
            decision.action,
            decision.score,
            decision.triggeredRules
                .joinToString(", ") { "${it.rule} (${it.detail})" }
                .ifEmpty { "none fired" },
        )

        return decision
    }

    private fun historyFor(candidate: ScreenCandidate): SenderHistory {
        val rules = properties.rules
        val row = attempts.historyFor(
            senderId = candidate.senderId,
            recipientId = candidate.recipientId,
            transactionId = candidate.transactionId,
            velocityFrom = candidate.screenedAt.minus(rules.velocity.window),
            fanOutFrom = candidate.screenedAt.minus(rules.fanOut.window),
            baselineFrom = candidate.screenedAt.minus(rules.amountAnomaly.window),
        )

        return SenderHistory(
            recentCount = row.recentCount,
            distinctRecipients = row.distinctRecipients,
            priorToRecipient = row.priorToRecipient,
            baselineCount = row.baselineCount,
            baselineMean = row.baselineMean,
        )
    }

    /**
     * Sum the weights of whatever fired, cap at 100, read off the action..
     */
    private fun decide(candidate: ScreenCandidate, history: SenderHistory): Decision {
        val triggered = rules.mapNotNull { it.evaluate(candidate, history) }
        val score = triggered.sumOf { it.weight }.coerceAtMost(MAX_SCORE)

        return Decision(
            action = properties.actionFor(score),
            score = score,
            triggeredRules = triggered,
        )
    }

    private fun recordAttempt(candidate: ScreenCandidate, decision: Decision) {
        val attempt = PaymentAttempt(
            transactionId = candidate.transactionId,
            senderId = candidate.senderId,
            recipientId = candidate.recipientId,
            amount = candidate.amount,
            currency = candidate.currency,
            screenedAt = candidate.screenedAt,
            correlationId = LogContext.current() ?: candidate.transactionId.toString(),
            decision = decision.action,
            score = decision.score,
            triggeredRules = jsonMapper.writeValueAsString(decision.triggeredRules),
        )

        entityManager.persist(attempt)
    }

    private fun publish(payload: PaymentEventPayload, decision: Decision) {
        fraudEvents.publish(
            eventType = eventTypeFor(decision.action),
            payload = payload,
            decision = FraudDecisionPayload(
                action = decision.action.name,
                score = decision.score,
                triggeredRules = decision.triggeredRules,
            ),
        )
    }

    private fun eventTypeFor(action: FraudAction): FraudEventType = when (action) {
        FraudAction.ALLOW -> FraudEventType.PAYMENT_CLEARED
        FraudAction.REVIEW -> FraudEventType.PAYMENT_HELD
        FraudAction.BLOCK -> FraudEventType.PAYMENT_BLOCKED
    }

    private fun clearWithoutScreening(payload: PaymentEventPayload): Decision {
        val decision = Decision(
            action = FraudAction.ALLOW,
            score = 0,
            triggeredRules = emptyList(),
        )

        publish(payload, decision)
        logger.info("Deposit cleared without screening")

        return decision
    }

    private companion object {
        const val MAX_SCORE = 100
    }
}