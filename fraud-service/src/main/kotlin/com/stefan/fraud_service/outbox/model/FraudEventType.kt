package com.stefan.fraud_service.outbox.model

enum class FraudEventType {
    PAYMENT_CLEARED,
    PAYMENT_HELD,
    PAYMENT_BLOCKED,
}