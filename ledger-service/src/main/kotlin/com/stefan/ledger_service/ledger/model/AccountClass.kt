package com.stefan.ledger_service.ledger.model

/**
 * normalBalance is the whole reason this type exists: it says which direction
 * increases the account. A DEBIT raises an ASSET and lowers a LIABILITY
 */
enum class AccountClass(val normalBalance: EntryDirection) {
    ASSET(EntryDirection.DEBIT),
    LIABILITY(EntryDirection.CREDIT),
}