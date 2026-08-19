package com.stefan.payment_api_service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PaymentApiServiceApplication

fun main(args: Array<String>) {
	runApplication<PaymentApiServiceApplication>(*args)
}
