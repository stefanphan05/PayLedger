package com.stefan.ledger_service.outbox.model

enum class LedgerEventType {
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
}