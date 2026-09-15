package com.stefan.fraud_service.outbox.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfig(
    private val properties: FraudEventProperties,
) {
    @Bean
    fun fraudEventsTopic(): NewTopic =
        TopicBuilder.name(properties.topic)
            .partitions(properties.partitions)
            .replicas(1)
            .build()
}