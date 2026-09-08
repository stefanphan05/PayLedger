package com.stefan.ledger_service.outbox.service

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drains the outbox on a timer.
 *
 * WHY THIS IS A SEPARATE BEAN FROM OutboxPublisher: putting @Scheduled and
 * @Transactional on the same bean silently does nothing transactional. The scheduler
 * holds a direct reference to the instance and calls the method on it, so Spring's
 * proxy - the thing that actually opens the transaction - is never involved. The
 * FOR UPDATE row locks would then be released the instant each SELECT returned, and
 * two app instances could send the same event twice. Calling ACROSS to another bean
 * goes through the proxy, so the transaction is real.
 */
@Component
@ConditionalOnProperty(name = ["ledger-events.polling-enabled"], havingValue = "true", matchIfMissing = true)
class OutboxPoller(
    private val publisher: OutboxPublisher,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ledger-events.poll-interval:1s}")
    fun poll() {
        try {
            publisher.publishBatch()
        } catch (e: Exception) {
            // The batch rolled back and its rows are still unpublished, so the next
            // tick retries. This log line is the ONLY signal the outbox has stopped
            // draining, so it must not go missing.
            logger.error("Outbox batch failed, retrying on the next poll", e)
        }
    }
}
