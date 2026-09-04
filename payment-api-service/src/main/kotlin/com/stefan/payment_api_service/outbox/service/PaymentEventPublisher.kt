package com.stefan.payment_api_service.outbox.service

import com.stefan.payment_api_service.outbox.model.PaymentEventEnvelope
import com.stefan.payment_api_service.outbox.model.OutboxEvent
import com.stefan.payment_api_service.transaction.model.Transaction
import com.stefan.payment_api_service.outbox.model.PaymentEventType
import com.stefan.payment_api_service.outbox.repository.OutboxEventRepository
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

/**
 * Records a payment event for delivery to Kafka.
 *
 * Writes a row and nothing else. There is no broker call here on purpose: this runs
 * inside the CALLER's database transaction, so the event row commits together with the
 * transaction it describes, or neither does. OutboxPoller does the actual sending.
 *
 * NOTE the reversal from the version this replaces, which caught everything and
 * logged. This one THROWS. A failure to insert the row must roll the payment back -
 * a committed payment with no event is precisely what ADR-0004 exists to prevent.
 * There is also nothing left worth swallowing: the old catch existed to stop a Kafka
 * outage failing a payment, and Kafka is no longer on this path.
 */
@Service
class PaymentEventPublisher(
    private val repository: OutboxEventRepository,
    private val jsonMapper: JsonMapper,
) {
    fun publish(eventType: PaymentEventType, transaction: Transaction) {
        val envelope = PaymentEventEnvelope.of(eventType, transaction)

        repository.save(
            OutboxEvent(
                transactionId = transaction.id,
                eventType = eventType,
                payload = jsonMapper.writeValueAsString(envelope),
            )
        )
    }
}