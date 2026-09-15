package com.stefan.fraud_service

import com.stefan.fraud_service.config.FraudProperties
import com.stefan.fraud_service.outbox.config.FraudEventProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(FraudEventProperties::class, FraudProperties::class)
class FraudServiceApplication

fun main(args: Array<String>) {
	runApplication<FraudServiceApplication>(*args)
}
