package com.stefan.ledger_service.ledger.consumer

import com.stefan.ledger_service.ledger.consumer.model.PaymentEventEnvelope
import com.stefan.ledger_service.ledger.model.ProcessedEvent
import com.stefan.ledger_service.ledger.repository.ProcessedEventRepository
import com.stefan.ledger_service.ledger.service.LedgerOutcome
import com.stefan.ledger_service.ledger.service.LedgerService
import com.stefan.ledger_service.shared.observability.LogContext
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class PaymentEventListener(
    private val jsonMapper: JsonMapper,
    private val ledgerService: LedgerService,
    private val processedEvents: ProcessedEventRepository,
    private val registry: MeterRegistry,
) {
    private var logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["payment-events"])
    fun onPaymentEvent(record: ConsumerRecord<String, String>) {
        MDC.putCloseable(LogContext.CORRELATION_ID, LogContext.of(record)).use {
            val event = parseEvent(record)
            MDC.putCloseable(LogContext.TRANSACTION_ID, event.transactionId.toString()).use {
                process(event)
            }
        }
    }

    private fun process(event: PaymentEventEnvelope) {
        if (!isPaymentInitiated(event)) {
            ignoreEvent(event)
            return
        }

        try {
            when (val outcome = ledgerService.apply(event)) {
                is LedgerOutcome.Applied -> {
                    logger.info("Applied transaction {}", event.transactionId)
                    countOutcome("completed", "none")
                }
                is LedgerOutcome.Rejected -> {
                    logger.warn("Rejected transaction {}: {}", event.transactionId, outcome.reason)
                    countOutcome("rejected", outcome.reason.name)
                }
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

    private fun countOutcome(outcome: String, reason: String) {
        Counter.builder("payledger.ledger.outcome")
            .tag("outcome", outcome)
            .tag("reason", reason)
            .register(registry)
            .increment()
    }

    private companion object {
        const val PAYMENT_INITIATED = "PAYMENT_INITIATED"
    }
}