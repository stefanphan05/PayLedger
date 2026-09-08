package com.stefan.ledger_service.ledger.consumer

import com.stefan.ledger_service.ledger.consumer.model.PaymentEventEnvelope
import com.stefan.ledger_service.ledger.model.ProcessedEvent
import com.stefan.ledger_service.ledger.repository.ProcessedEventRepository
import com.stefan.ledger_service.ledger.service.LedgerOutcome
import com.stefan.ledger_service.ledger.service.LedgerService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class PaymentEventListener(
    private val jsonMapper: JsonMapper,
    private val ledgerService: LedgerService,
    private val processedEvents: ProcessedEventRepository
) {
    private var logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["payment-events"])
    fun onPaymentEvent(record: ConsumerRecord<String, String>) {
        val event = parseEvent(record)

        if (!isPaymentInitiated(event)) {
            ignoreEvent(event)
            return
        }

        try {
            when (val outcome = ledgerService.apply(event)) {
                is LedgerOutcome.Applied -> logger.info("Applied transaction {}", event.transactionId)
                is LedgerOutcome.Rejected -> logger.warn("Rejected transaction {}: {}", event.transactionId, outcome.reason)
            }
        } catch (e: DataIntegrityViolationException) {
            if (processedEvents.existsById(event.eventId)) {
                logger.info("Duplicate delivery of event {}, already applied", event.eventId)
            } else {
                throw e
            }
        }
    }

    private fun parseEvent(
        record: ConsumerRecord<String, String>
    ): PaymentEventEnvelope {
        return jsonMapper.readValue(
            record.value(),
            PaymentEventEnvelope::class.java
        )
    }


    private fun isPaymentInitiated(
        event: PaymentEventEnvelope,
    ): Boolean {
        return event.eventType == PAYMENT_INITIATED
    }

    private fun ignoreEvent(
        event: PaymentEventEnvelope,
    ) {
        logger.debug(
            "Ignoring {} for transaction {}",
            event.eventType,
            event.transactionId,
        )
    }

    private companion object {
        const val PAYMENT_INITIATED = "PAYMENT_INITIATED"
    }
}