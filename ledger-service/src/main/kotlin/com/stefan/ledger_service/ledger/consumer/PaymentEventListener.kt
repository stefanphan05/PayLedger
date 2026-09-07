package com.stefan.ledger_service.ledger.consumer

import com.stefan.ledger_service.ledger.consumer.model.PaymentEventEnvelope
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class PaymentEventListener(
    private val jsonMapper: JsonMapper,
) {
    private var logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["payment-events"])
    fun onPaymentEvent(record: ConsumerRecord<String, String>) {
        val envelope = jsonMapper.readValue(record.value(), PaymentEventEnvelope::class.java)

        if (envelope.eventType != PAYMENT_INITIATED) {
            logger.debug(
                "Ignoring {} for transaction {}",
                envelope.eventType,
                envelope.transactionId,
            )
            return
        }

        val payload = envelope.payload
        logger.info(
            "PAYMENT_INITIATED event={} transaction={} {} {} {} -> {}",
            envelope.eventId,
            payload.transactionId,
            payload.amount,
            payload.currency,
            payload.senderId,
            payload.recipientId,
        )
    }

    private companion object {
        const val PAYMENT_INITIATED = "PAYMENT_INITIATED"
    }
}