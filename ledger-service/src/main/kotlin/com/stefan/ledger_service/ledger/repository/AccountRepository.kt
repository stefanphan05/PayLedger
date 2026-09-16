package com.stefan.ledger_service.ledger.repository

import com.stefan.ledger_service.ledger.model.Account
import com.stefan.ledger_service.ledger.model.AccountClass
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AccountRepository: JpaRepository<Account, UUID> {
    /**
     * The account holding the platform's cash in one currency. One per currency
     * by convention only, so this takes the first rather than assuming exactly one.
     */
    fun findFirstByAccountClassAndCurrency(accountClass: AccountClass, currency: String): Account?
}