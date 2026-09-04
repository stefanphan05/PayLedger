package com.stefan.payment_api_service.outbox.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "payment-events")
data class PaymentEventProperties(
    /** The single topic every payment event is published to. */
    val topic: String = "payment-events",

    /** The number of partitions for the payment events topic. */
    val partitions: Int = 3,

    /** Wait between one batch finishing and the next starting. This is the floor on
     *  how stale an event can be: a payment's event reaches Kafka within about this
     *  long of the commit. */
    val pollInterval: Duration = Duration.ofSeconds(1),

    /** Rows per batch. Bounds how long one transaction holds its row locks waiting
     *  on Kafka. */
    val batchSize: Int = 100,
    /** How long to wait for the broker to acknowledge one record before giving up on
     *  the batch and letting the next poll retry it. */
    val sendTimeout: Duration = Duration.ofSeconds(5),

    /** Off in tests, so the scheduler does not race the assertions. */
    val pollingEnabled: Boolean = true,
)
