package com.stefan.ledger_service.shared.observability

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.MDC
import java.nio.charset.StandardCharsets

/**
 * The two ids every log line in a flow should carry.
 *
 * correlationId answers "what happened in this one flow".
 * transactionId answers "everything that ever happened to this payment".
 * Both live in MDC, and Boot's JSON encoder folds MDC into every line on its own - nothing has to pass them around.
 *
 * Using object here is singleton, it means kotlin creates and manages it for you
 */
object LogContext {
    const val CORRELATION_ID = "correlationId"
    const val TRANSACTION_ID = "transactionId"

    /**
     * The header name, on HTTP requests and on Kafka records alike
     */
    const val HEADER = "X-Correlation-Id"

    const val MAX_LENGTH = 64

    fun current(): String? {
        return MDC.get(CORRELATION_ID)
    }

    /**
     * Validates correlation IDs coming from external systems.
     * Only allows safe characters to prevent log injection.
     */
    fun sanitise(value: String?): String? {
        if (value == null) return null

        val id = value.trim()

        if (id.isEmpty() || id.length > MAX_LENGTH) return null

        if (!id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null

        return id
    }

    /**
     * Gets correlation ID from Kafka message.
     * If missing, use transaction ID because old messages
     * may not have correlation headers.
     */
    fun of(record: ConsumerRecord<String, String>): String {
        val headerId = record.headers()
            .lastHeader(HEADER)
            ?.value()
            ?.toString(StandardCharsets.UTF_8)

        return sanitise(headerId)
            ?: sanitise(record.key())
            ?: "unknown"
    }
}