package com.stefan.ledger_service.ledger.model

enum class RejectionReason(val message: String) {
    CURRENCY_MISMATCH("Sender and recipient must use the same currency."),
    SELF_TRANSFER("Cannot transfer to the same account."),
    INSUFFICIENT_FUNDS("Insufficient funds.");
}