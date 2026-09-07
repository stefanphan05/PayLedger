package com.stefan.ledger_service.ledger.consumer

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class PaymentEventListener {
    private var logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["payment-events"])
    fun onPaymentEvent(record: ConsumerRecord<String, String>) {
        // Key is the transaction UUID: the API keys by it so every event for one
        // payment lands on the same partition and stays ordered relative it itself
        logger.info(
            "payment-events p{}@{} key={} value={}",
            record.partition(),
            record.offset(),
            record.key(),
            record.value(),
        )
    }
}