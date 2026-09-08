package com.stefan.ledger_service.ledger.repository

import com.stefan.ledger_service.ledger.model.LedgerEntry
import org.springframework.data.jpa.repository.JpaRepository

interface LedgerEntryRepository: JpaRepository<LedgerEntry, Long> {
}