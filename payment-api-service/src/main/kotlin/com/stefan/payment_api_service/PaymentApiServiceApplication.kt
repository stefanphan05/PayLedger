package com.stefan.payment_api_service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class PaymentApiServiceApplication

fun main(args: Array<String>) {
	runApplication<PaymentApiServiceApplication>(*args)
}
