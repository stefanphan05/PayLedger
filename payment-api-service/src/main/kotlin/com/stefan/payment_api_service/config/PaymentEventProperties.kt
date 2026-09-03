package com.stefan.payment_api_service.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "payment-events")
data class PaymentEventProperties(
    /** The single topic every payment event is published to. */
    val topic: String = "payment-events",

    /** The number of partitions for the payment events topic. */
    val partitions: Int = 3
)
