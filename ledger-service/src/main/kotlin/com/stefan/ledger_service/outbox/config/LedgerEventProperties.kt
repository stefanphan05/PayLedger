package com.stefan.ledger_service.outbox.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ledger-events")
data class LedgerEventProperties(
    /** The single topic the ledger publishes its verdicts to. */
    val topic: String = "ledger-events",
    val partitions: Int = 3,

    /** Wait between one batch finishing and the next starting. The floor on how
     *  stale a verdict can be. */
    val pollInterval: Duration = Duration.ofSeconds(1),

    /** Rows per batch. Bounds how long one transaction holds its row locks. */
    val batchSize: Int = 100,

    val sendTimeout: Duration = Duration.ofSeconds(5),

    /** Off in tests, so the scheduler does not race the assertions. */
    val pollingEnabled: Boolean = true,
)