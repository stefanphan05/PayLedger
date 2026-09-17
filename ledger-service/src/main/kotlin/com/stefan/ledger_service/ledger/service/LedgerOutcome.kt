package com.stefan.ledger_service.ledger.service

import com.stefan.ledger_service.ledger.model.Refusal
import com.stefan.ledger_service.ledger.model.RejectionReason

sealed interface LedgerOutcome {
    data object Applied: LedgerOutcome
    data class Rejected(
        val reason: RejectionReason,
        val detail: String = reason.message,
    ) : LedgerOutcome {
        constructor(refusal: Refusal) : this(
            reason = refusal.reason,
            detail = refusal.detail,
        )
    }
}