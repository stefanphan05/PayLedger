package com.stefan.payment_api_service.transaction.model

enum class TransactionStatus(val type: String) {
    PENDING("PENDING"),
    UNDER_REVIEW("UNDER_REVIEW"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED")
}