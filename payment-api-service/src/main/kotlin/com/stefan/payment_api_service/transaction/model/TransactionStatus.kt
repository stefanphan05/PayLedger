package com.stefan.payment_api_service.transaction.model

enum class TransactionStatus(val type: String) {
    PENDING("PENDING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED")
}