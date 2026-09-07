package com.stefan.ledger_service.outbox.service

import com.stefan.ledger_service.outbox.config.LedgerEventProperties
import com.stefan.ledger_service.outbox.repository.OutboxEventRepository
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
    private val properties: LedgerEventProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * All or nothing per batch. If a send fails this throws, the transaction rolls
     * back, nothing is marked published, and the next poll retries from the top.
     * Events already sent before the failure go out twice, which is why the envelope
     * carries an eventId for the consumer to dedupe on.
     *
     * rollbackFor is NOT optional. Spring rolls back on unchecked exceptions only,
     * and Future.get() throws ExecutionException and TimeoutException, both checked,
     * so the default would COMMIT a half sent batch. Kotlin hides this completely:
     * it has no checked exceptions, so nothing below hints that these paths differ.
     */
    @Transactional(rollbackFor = [Exception::class])
    fun publishBatch() {
        val events = repository.lockUnpublishedBatch(properties.batchSize)
        if (events.isEmpty()) return

        val sentAt = Instant.now()
        events.forEach { event ->
            // .get(), not fire and forget: the row is only marked published once the
            // broker has accepted the record.
            val metadata = kafkaTemplate
                .send(properties.topic, event.transactionId.toString(), event.payload)
                .get(properties.sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .recordMetadata

            event.publishedAt = sentAt

            // Logged once the broker has acknowledged this record, so it means the
            // event really is on the topic at that partition and offset.
            //
            // Note it can still be sent AGAIN: if a later send in this batch fails,
            // the whole transaction rolls back, publishedAt is never committed, and
            // the next poll resends from the top. That is the at-least-once delivery
            // the consumer's eventId guard exists for.
            logger.info(
                "Published {} for transaction {} to {}-{}@{}",
                event.eventType,
                event.transactionId,
                metadata.topic(),
                metadata.partition(),
                metadata.offset(),
            )
        }

        logger.debug("Published a batch of {} ledger events", events.size)
    }
}