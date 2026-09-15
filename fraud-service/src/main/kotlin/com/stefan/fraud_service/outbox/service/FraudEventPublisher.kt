package com.stefan.fraud_service.outbox.service

import com.stefan.fraud_service.consumer.model.PaymentEventPayload
import com.stefan.fraud_service.outbox.model.FraudDecisionPayload
import com.stefan.fraud_service.outbox.model.FraudEventEnvelope
import com.stefan.fraud_service.outbox.model.FraudEventType
import com.stefan.fraud_service.outbox.model.OutboxEvent
import com.stefan.fraud_service.outbox.repository.OutboxEventRepository
import com.stefan.fraud_service.shared.observability.LogContext
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

/**
 * Writes a row and nothing else, inside the CALLER's transaction — so the decision and
 * the event announcing it commit together or not at all. Same shape as
 * LedgerEventPublisher, same reason: ADR-0004.
 */
@Service
class FraudEventPublisher(
    private val repository: OutboxEventRepository,
    private val jsonMapper: JsonMapper,
) {
    fun publish(
        eventType: FraudEventType,
        payload: PaymentEventPayload,
        decision: FraudDecisionPayload,
    ) {
        val envelope = FraudEventEnvelope.of(eventType, payload, decision)

        repository.save(
            OutboxEvent(
                transactionId = payload.transactionId,
                eventType = eventType,
                payload = jsonMapper.writeValueAsString(envelope),
                correlationId = LogContext.current() ?: payload.transactionId.toString(),
            )
        )
    }
}