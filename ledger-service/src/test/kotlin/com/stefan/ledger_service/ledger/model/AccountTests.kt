package com.stefan.ledger_service.ledger.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal
import java.util.UUID

/**
 * The rule every balance in the system depends on: whether an entry adds or subtracts
 * depends on the account's class, not on the direction alone.
 *
 * No Spring, no containers. This is arithmetic.
 */
class AccountTests {

    @ParameterizedTest
    @EnumSource(AccountClass::class)
    fun `an entry in the account's normal direction increases the balance`(
        accountClass: AccountClass,
    ) {
        val account = account(accountClass, "100.00")

        account.applyEntry(accountClass.normalBalance, BigDecimal("25.00"))

        assertBalance("125.00", account)
    }

    @ParameterizedTest
    @EnumSource(AccountClass::class)
    fun `an entry against the normal direction decreases the balance`(
        accountClass: AccountClass,
    ) {
        val account = account(accountClass, "100.00")

        account.applyEntry(accountClass.normalBalance.opposite(), BigDecimal("25.00"))

        assertBalance("75.00", account)
    }

    /**
     * The two tests above stay green even if ASSET and LIABILITY swap their
     * normalBalance, because they ask the enum what it expects. This pins the actual
     * semantics, so an inverted mapping fails here instead of silently reversing
     * every balance in the ledger.
     */
    @Test
    fun `DEBIT raises an ASSET and lowers a LIABILITY`() {
        val funding = account(AccountClass.ASSET, "0.00")
        val wallet = account(AccountClass.LIABILITY, "100.00")

        funding.applyEntry(EntryDirection.DEBIT, BigDecimal("50.00"))
        wallet.applyEntry(EntryDirection.DEBIT, BigDecimal("50.00"))

        assertBalance("50.00", funding)
        assertBalance("50.00", wallet)
    }

    /**
     * Money entering the system raises both sides. Money moving inside it raises one
     * wallet, lowers another, and must not touch the funding account at all.
     */
    @Test
    fun `a deposit moves both sides, a transfer moves only the wallets`() {
        val funding = account(AccountClass.ASSET, "0.00")
        val alice = account(AccountClass.LIABILITY, "0.00")
        val bob = account(AccountClass.LIABILITY, "0.00")

        funding.applyEntry(EntryDirection.DEBIT, BigDecimal("1000.00"))
        alice.applyEntry(EntryDirection.CREDIT, BigDecimal("1000.00"))

        alice.applyEntry(EntryDirection.DEBIT, BigDecimal("50.00"))
        bob.applyEntry(EntryDirection.CREDIT, BigDecimal("50.00"))

        assertBalance("1000.00", funding)
        assertBalance("950.00", alice)
        assertBalance("50.00", bob)
    }

    @Test
    fun `canCover is true at exactly the balance and false one cent over`() {
        val account = account(AccountClass.LIABILITY, "100.00")

        assertEquals(true, account.canCover(BigDecimal("100.00")))
        assertEquals(false, account.canCover(BigDecimal("100.01")))
    }

    private fun EntryDirection.opposite() =
        if (this == EntryDirection.DEBIT) EntryDirection.CREDIT else EntryDirection.DEBIT

    private fun account(accountClass: AccountClass, balance: String) = Account(
        id = UUID.randomUUID(),
        accountClass = accountClass,
        currency = "AUD",
        balance = BigDecimal(balance),
    )

    // compareTo, not equals: BigDecimal("125.00") != BigDecimal("125.0000"), because
    // equals compares scale as well as value.
    private fun assertBalance(expected: String, account: Account) =
        assertEquals(0, BigDecimal(expected).compareTo(account.balance), "was ${account.balance}")
}
