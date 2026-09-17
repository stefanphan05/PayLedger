package com.stefan.payment_api_service.fraud.model

import com.stefan.payment_api_service.transaction.model.TransactionStatus

enum class FraudVerdict(
    val appliesTo: Set<TransactionStatus>,
    val newStatus: TransactionStatus,
    val failureReason: String? = null,
    val failureDetail: String? = null,
) {
    CLEARED(
        appliesTo = setOf(TransactionStatus.UNDER_REVIEW),
        newStatus = TransactionStatus.PENDING,
    ),

    HELD(
        appliesTo = setOf(TransactionStatus.PENDING),
        newStatus = TransactionStatus.UNDER_REVIEW,
    ),

    BLOCKED(
        appliesTo = setOf(TransactionStatus.PENDING, TransactionStatus.UNDER_REVIEW),
        newStatus = TransactionStatus.FAILED,
        failureReason = "FRAUD_BLOCKED",
        failureDetail = "This payment was stopped by fraud screening.",
    );

    companion object {
        fun of(eventType: String): FraudVerdict? = when (eventType) {
            "PAYMENT_CLEARED" -> CLEARED
            "PAYMENT_HELD" -> HELD
            "PAYMENT_BLOCKED" -> BLOCKED
            else -> null
        }
    }
}