package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.config.PaymentEventProperties
import com.stefan.payment_api_service.models.dto.PaymentEventEnvelope
import com.stefan.payment_api_service.models.entity.Transaction
import com.stefan.payment_api_service.models.enum.PaymentEventType
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

@Service
class PaymentEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jsonMapper: JsonMapper,
    private val properties: PaymentEventProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun publish(eventType: PaymentEventType, transaction: Transaction) {
        val envelope = PaymentEventEnvelope.of(eventType, transaction)

        try {
            kafkaTemplate.send(
                properties.topic,
                transaction.id.toString(),
                jsonMapper.writeValueAsString(envelope),
            ).whenComplete { _, failure ->
                if (failure != null) logFailure(envelope, failure)
            }
        } catch (e: Exception) {
            logFailure(envelope, e)
        }
    }

    private fun logFailure(envelope: PaymentEventEnvelope, cause: Throwable) {
        logger.error(
            "LOST payment event: {} {} for transaction {} was not published",
            envelope.eventType, envelope.eventId, envelope.transactionId, cause,
        )
    }
}