package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.config.PaymentEventProperties
import com.stefan.payment_api_service.repository.OutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.TimeUnit

@Service
class OutboxPublisher(
    private val repository: OutboxEventRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val properties: PaymentEventProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Sends one batch of unsent events and marks them published.
     *
     * All or nothing per batch: if any send fails, this throws, the transaction rolls
     * back, nothing is marked published, and the next poll retries the batch from the
     * top. Events that did go out before the failure are sent a second time - which is
     * exactly why the envelope carries an eventId for consumers to dedupe on, and why
     * ADR-0004 lists downstream idempotency as something we give up.
     *
     * rollbackFor is NOT optional here. Spring rolls back on unchecked exceptions only,
     * and CompletableFuture.get() throws ExecutionException / TimeoutException, both
     * CHECKED - so the default would COMMIT a half-sent batch. Kotlin makes that
     * invisible: it has no checked exceptions, so nothing in the code below hints that
     * these two failure paths are treated differently. Worse, send() can also throw
     * KafkaException synchronously, which IS unchecked, so the same method would roll
     * back or commit depending on where the broker failed. This makes it one behaviour.
     */
    @Transactional(rollbackFor = [Exception::class])
    fun publishBatch() {
        val events = repository.lockUnpublishedBatch(properties.batchSize)
        if (events.isEmpty()) return

        val sentAt = Instant.now()
        events.forEach { event ->
            // .get() and not fire-and-forget: we have to know the broker ACCEPTED the
            // record before marking the row published. Marking it optimistically is
            // the lost event all over again, one table further along.
            kafkaTemplate
                .send(properties.topic, event.transactionId.toString(), event.payload)
                .get(properties.sendTimeout.toMillis(), TimeUnit.MILLISECONDS)

            event.publishedAt = sentAt
        }

        logger.debug("Published {} outbox events", events.size)
    }
}