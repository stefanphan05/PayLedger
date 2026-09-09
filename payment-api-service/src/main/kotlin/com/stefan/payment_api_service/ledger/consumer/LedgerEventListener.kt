package com.stefan.payment_api_service.ledger.consumer

import com.stefan.payment_api_service.exception.transaction.TransactionNotFoundException
import com.stefan.payment_api_service.ledger.model.LedgerEventEnvelope
import com.stefan.payment_api_service.ledger.repository.ProcessedEventRepository
import com.stefan.payment_api_service.shared.observability.LogContext
import com.stefan.payment_api_service.transaction.model.TransactionStatus
import com.stefan.payment_api_service.transaction.service.TransactionService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class LedgerEventListener(
    private val jsonMapper: JsonMapper,
    private val transactionService: TransactionService,
    private val processedEvents: ProcessedEventRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${ledger-events.topic}"])
    fun onLedgerEvent(record: ConsumerRecord<String, String>) {
        MDC.putCloseable(LogContext.CORRELATION_ID, LogContext.of(record)).use {
            handle(record)
        }
    }

    private fun handle(record: ConsumerRecord<String, String>) {
        val event = parseEvent(record)

        // Only care about PAYMENT_COMPLETED or PAYMENT_FAILED, ignore everything else
        val status = statusFor(event.eventType) ?: run {
            ignoreEvent(event)
            return
        }

        try {
            transactionService.settle(event, status)
        } catch (e: DataIntegrityViolationException) {
            if (processedEvents.existsById(event.eventId)) {
                logger.info("Duplicate delivery of event {}, already applied", event.eventId)
            } else {
                throw e
            }
        } catch (e: TransactionNotFoundException) {
            // Dropped on purpose. We were told about a payment we have no record of,
            // and trying again will not make it appear.
            // Feature 08 adds a dead letter topic, which is a better home than a log line.
            logger.error("Verdict for unknown transaction {}", event.transactionId, e)
        }
    }

    private fun parseEvent(
        record: ConsumerRecord<String, String>,
    ): LedgerEventEnvelope {
        return jsonMapper.readValue(
            record.value(),
            LedgerEventEnvelope::class.java
        )
    }

    private fun statusFor(eventType: String): TransactionStatus? {
        return when(eventType) {
            PAYMENT_COMPLETED -> TransactionStatus.COMPLETED
            PAYMENT_FAILED -> TransactionStatus.FAILED
            else -> null
        }
    }

    private fun ignoreEvent(event: LedgerEventEnvelope) {
        logger.debug("Ignoring {} for transaction {}", event.eventType, event.transactionId)
    }

    private companion object {
        const val PAYMENT_COMPLETED = "PAYMENT_COMPLETED"
        const val PAYMENT_FAILED = "PAYMENT_FAILED"
    }
}