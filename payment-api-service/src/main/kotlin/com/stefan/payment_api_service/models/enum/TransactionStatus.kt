package com.stefan.payment_api_service.models.enum

enum class TransactionStatus(val type: String) {
    PENDING("PENDING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED")
}