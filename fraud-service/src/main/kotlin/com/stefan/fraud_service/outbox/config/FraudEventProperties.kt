package com.stefan.fraud_service.outbox.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "fraud-events")
data class FraudEventProperties(
    /** The single topic this service publishes verdicts to. One producer per topic. */
    val topic: String = "fraud-events",
    val partitions: Int = 3,

    /** Wait between one batch finishing and the next starting. This is the floor on
     *  how long a cleared payment waits before the ledger sees it, so it is also the
     *  floor on how much this feature adds to settlement latency. */
    val pollInterval: Duration = Duration.ofSeconds(1),

    val batchSize: Int = 100,
    val sendTimeout: Duration = Duration.ofSeconds(5),

    /** Off in tests, so the scheduler does not race the assertions. */
    val pollingEnabled: Boolean = true,
)