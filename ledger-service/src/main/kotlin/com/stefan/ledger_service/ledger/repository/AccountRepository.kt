package com.stefan.ledger_service.ledger.repository

import com.stefan.ledger_service.ledger.model.Account
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AccountRepository: JpaRepository<Account, UUID> {
}