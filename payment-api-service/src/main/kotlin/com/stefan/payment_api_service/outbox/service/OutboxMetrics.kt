package com.stefan.payment_api_service.outbox.service

import com.stefan.payment_api_service.outbox.repository.OutboxEventRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * How far behind the outbox is.
 *
 * This is the early warning that the poller has stalled or Kafka is unreachable, and
 * it shows before anyone complains: payments still commit, they just stop reaching the
 * ledger. Alert on the AGE, not the count - a big batch is normal, an old unsent row
 * never is.
 */
@Component
class OutboxMetrics(
    private val repository: OutboxEventRepository,
    registry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        Gauge.builder("payledger.outbox.unpublished", this) { it.unpublished() }
            .description("Outbox rows the poller has not yet sent to Kafka")
            .register(registry)

        Gauge.builder("payledger.outbox.oldest.age", this) { it.oldestAgeSeconds() }
            .description("Age of the oldest unsent outbox row")
            .baseUnit("seconds")
            .register(registry)
    }

    private fun unpublished(): Double = safely {
        repository.countByPublishedAtIsNull().toDouble()
    }

    private fun oldestAgeSeconds(): Double = safely {
        val oldest = repository.findFirstByPublishedAtIsNullOrderByIdAsc()?.createdAt
            ?: return@safely 0.0

        Duration.between(oldest, Instant.now()).toMillis() / 1000.0
    }

    private inline fun safely(block: () -> Double): Double =
        try {
            block()
        } catch (e: Exception) {
            logger.warn("Outbox gauge read failed", e)
            0.0
        }
}