package com.stefan.payment_api_service.outbox.model

enum class PaymentEventType {
    PAYMENT_INITIATED,
    PAYMENT_STATUS_CHANGED,
}