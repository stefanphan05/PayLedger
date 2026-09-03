package com.stefan.payment_api_service.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfig(
    private val properties: PaymentEventProperties
) {
    @Bean
    fun paymentEventsTopic(): NewTopic =
        TopicBuilder.name(properties.topic)
            .partitions(properties.partitions)
            .replicas(1)
            .build()
}