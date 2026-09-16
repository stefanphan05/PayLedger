package com.stefan.ledger_service.ledger.model

enum class RejectionReason(val message: String) {
    CURRENCY_MISMATCH("Sender and recipient must use the same currency."),
    SELF_TRANSFER("Cannot transfer to the same account."),
    INSUFFICIENT_FUNDS("Insufficient funds."),
    NO_FUNDING_ACCOUNT("No funding account exists for this currency."),
    FUNDING_ACCOUNT_SHORT("The platform float cannot cover this withdrawal."),
    MALFORMED_PAYMENT("The payment did not name the accounts it needed.");
}