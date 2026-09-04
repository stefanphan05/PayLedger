package com.stefan.payment_api_service.outbox.service

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
@ConditionalOnProperty(name = ["payment-events.polling-enabled"], havingValue = "true", matchIfMissing = true)
class OutboxPoller(
    private val publisher: OutboxPublisher,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${payment-events.poll-interval:1s}")
    fun poll() {
        try {
            publisher.publishBatch()
        } catch (e: Exception) {
            // The batch rolled back and its rows are still unpublished, so the next
            // tick picks them up again - nothing is lost by swallowing this.
            //
            // This log line is the ONLY signal that the outbox has stopped draining,
            // so it must not go missing: catching here means Spring's own scheduler
            // error handler never sees the failure and never logs it for us.
            logger.error("Outbox batch failed, retrying on the next poll", e)
        }
    }
}
