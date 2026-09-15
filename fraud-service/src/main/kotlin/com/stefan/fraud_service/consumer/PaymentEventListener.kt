package com.stefan.fraud_service.consumer

import com.stefan.fraud_service.consumer.model.PaymentEventEnvelope
import com.stefan.fraud_service.screening.repository.ProcessedEventRepository
import com.stefan.fraud_service.screening.service.ScreeningService
import com.stefan.fraud_service.shared.observability.LogContext
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
    private val screeningService: ScreeningService,
    private val processedEvents: ProcessedEventRepository,
    private val registry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${payment-events.topic}"])
    fun onPaymentEvent(record: ConsumerRecord<String, String>) {
        MDC.putCloseable(LogContext.CORRELATION_ID, LogContext.of(record)).use {
            val event = parseEvent(record)
            MDC.putCloseable(LogContext.TRANSACTION_ID, event.transactionId.toString()).use {
                process(event)
            }
        }
    }

    private fun process(event: PaymentEventEnvelope) {
        if (event.eventType != PAYMENT_INITIATED) {
            logger.debug("Ignoring {} for transaction {}", event.eventType, event.transactionId)
            return
        }

        try {
            val decision = screeningService.screen(event)
            countDecision(decision.action.name)
            decision.triggeredRules.forEach { countRule(it.rule) }
        } catch (e: DataIntegrityViolationException) {
            if (processedEvents.existsById(event.eventId)) {
                logger.info("Duplicate delivery of event {}, already screened", event.eventId)
            } else {
                throw e
            }
        }
    }

    private fun parseEvent(record: ConsumerRecord<String, String>): PaymentEventEnvelope =
        jsonMapper.readValue(record.value(), PaymentEventEnvelope::class.java)

    /** Tracks overall fraud outcomes (e.g., APPROVE, REJECT) for dashboard metrics and alerting. */
    private fun countDecision(action: String) {
        registry.counter("payledger.fraud.decision", "action", action).increment()
    }

    /** Tracks how frequently each specific fraud detection rule is triggered. */
    private fun countRule(rule: String) {
        registry.counter("payledger.fraud.rule", "rule", rule).increment()
    }

    private companion object {
        const val PAYMENT_INITIATED = "PAYMENT_INITIATED"
    }
}