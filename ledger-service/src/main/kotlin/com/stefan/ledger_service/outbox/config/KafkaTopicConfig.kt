package com.stefan.ledger_service.outbox.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfig(
    private val properties: LedgerEventProperties,
) {
    @Bean
    fun ledgerEventsTopic(): NewTopic =
        TopicBuilder.name(properties.topic)
            .partitions(properties.partitions)
            .replicas(1)
            .build()
}