package com.stefan.payment_api_service.fraud.consumer

import com.stefan.payment_api_service.exception.transaction.TransactionNotFoundException
import com.stefan.payment_api_service.fraud.model.FraudEventEnvelope
import com.stefan.payment_api_service.fraud.model.FraudVerdict
import com.stefan.payment_api_service.ledger.repository.ProcessedEventRepository
import com.stefan.payment_api_service.shared.observability.LogContext
import com.stefan.payment_api_service.transaction.service.TransactionService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class FraudEventListener(
    private val jsonMapper: JsonMapper,
    private val transactionService: TransactionService,
    private val processedEvents: ProcessedEventRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${fraud-events.topic}"])
    fun onFraudEvent(record: ConsumerRecord<String, String>) {
        MDC.putCloseable(LogContext.CORRELATION_ID, LogContext.of(record)).use {
            handle(record)
        }
    }

    private fun handle(record: ConsumerRecord<String, String>) {
        val event = parseEvent(record)

        val verdict = FraudVerdict.of(event.eventType) ?: run {
            logger.debug("Ignoring {} for transaction {}", event.eventType, event.transactionId)
            return
        }

        try {
            transactionService.applyFraudVerdict(event, verdict)
        } catch (e: DataIntegrityViolationException) {
            if (processedEvents.existsById(event.eventId)) {
                logger.info("Duplicate delivery of event {}, already applied", event.eventId)
            } else {
                throw e
            }
        } catch (e: TransactionNotFoundException) {
            logger.error("Fraud verdict for unknown transaction {}", event.transactionId, e)
        }
    }

    private fun parseEvent(record: ConsumerRecord<String, String>): FraudEventEnvelope =
        jsonMapper.readValue(record.value(), FraudEventEnvelope::class.java)
}