package com.stefan.ledger_service.ledger.service

import com.stefan.ledger_service.ledger.consumer.model.PaymentEventEnvelope
import com.stefan.ledger_service.ledger.consumer.model.PaymentEventPayload
import com.stefan.ledger_service.ledger.model.Account
import com.stefan.ledger_service.ledger.model.AccountClass
import com.stefan.ledger_service.ledger.model.EntryDirection
import com.stefan.ledger_service.ledger.model.LedgerEntry
import com.stefan.ledger_service.ledger.model.Refusal
import com.stefan.ledger_service.ledger.model.RejectionReason
import com.stefan.ledger_service.ledger.repository.AccountRepository
import com.stefan.ledger_service.ledger.repository.LedgerEntryRepository
import com.stefan.ledger_service.ledger.repository.ProcessedEventRepository
import com.stefan.ledger_service.outbox.model.LedgerEventType
import com.stefan.ledger_service.outbox.service.LedgerEventPublisher
import jakarta.persistence.EntityManager
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Service
class LedgerService(
    private val accounts: AccountRepository,
    private val entries: LedgerEntryRepository,
    private val processedEvents: ProcessedEventRepository,
    private val entityManager: EntityManager,
    private val ledgerEvents: LedgerEventPublisher,
) {
    /**
     * Everything for one even in ONE transaction
     * Commit everything together or NOT AT ALL.
     */
    @Transactional
    fun apply(envelope: PaymentEventEnvelope): LedgerOutcome {
        markEventProcessed(envelope)

        val payment = envelope.payload

        return when (val accounts = accountsFor(payment)) {
            is PaymentAccounts.CouldNotFind -> refuse(payment, accounts.refusal)
            is PaymentAccounts.Found -> post(payment, accounts)
        }
    }

    // ---------------------------------------------------------------- accounts
    private sealed interface PaymentAccounts {
        data class Found(val debited: Account, val credited: Account) : PaymentAccounts
        data class CouldNotFind(val refusal: Refusal) : PaymentAccounts
    }

    private fun accountsFor(payment: PaymentEventPayload): PaymentAccounts = when (payment.type) {
        PaymentEventPayload.DEPOSIT -> depositAccounts(payment)
        PaymentEventPayload.WITHDRAWAL -> withdrawalAccounts(payment)
        else -> transferAccounts(payment)
    }

    /** One user pays another. Money leaves one wallet and lands in the other. */
    private fun transferAccounts(payment: PaymentEventPayload): PaymentAccounts {
        val sender = payment.senderId
            ?: return PaymentAccounts.CouldNotFind(Refusal(RejectionReason.MALFORMED_PAYMENT))
        val recipient = payment.recipientId
            ?: return PaymentAccounts.CouldNotFind(Refusal(RejectionReason.MALFORMED_PAYMENT))

        return PaymentAccounts.Found(
            debited = walletFor(sender, payment.currency),
            credited = walletFor(recipient, payment.currency),
        )
    }

    /**
     * Money arriving from outside. The platform now holds more cash, and it owes
     * more to the user, so both sides go up. Nothing inside the system pays for it.
     */
    private fun depositAccounts(payment: PaymentEventPayload): PaymentAccounts {
        val platformCash = fundingAccountFor(payment.currency)
            ?: return PaymentAccounts.CouldNotFind(Refusal(RejectionReason.NO_FUNDING_ACCOUNT))
        val user = payment.recipientId
            ?: return PaymentAccounts.CouldNotFind(Refusal(RejectionReason.MALFORMED_PAYMENT))

        return PaymentAccounts.Found(
            debited = platformCash,
            credited = walletFor(user, payment.currency),
        )
    }

    /** Money leaving. The mirror of a deposit: both sides go down. */
    private fun withdrawalAccounts(payment: PaymentEventPayload): PaymentAccounts {
        val platformCash = fundingAccountFor(payment.currency)
            ?: return PaymentAccounts.CouldNotFind(Refusal(RejectionReason.NO_FUNDING_ACCOUNT))
        val user = payment.senderId
            ?: return PaymentAccounts.CouldNotFind(Refusal(RejectionReason.MALFORMED_PAYMENT))

        return PaymentAccounts.Found(
            debited = walletFor(user, payment.currency),
            credited = platformCash,
        )
    }

    private fun fundingAccountFor(currency: String): Account? =
        accounts.findFirstByAccountClassAndCurrency(AccountClass.ASSET, currency)

    /**
     * This service doesn't have user directory, it learns that an account exists only because an event mentioned it
     * so an unknown id means not seen yet, not invalid. The new wallet starts at zero, so the payment that created
     * it will be rejected for insufficient funds, which is correct
     */
    private fun walletFor(id: UUID, currency: String): Account {
        return accounts.findByIdOrNull(id) ?: createWallet(id, currency)
    }

    private fun createWallet(id: UUID, currency: String): Account {
        val account = Account(
            id = id,
            accountClass = AccountClass.LIABILITY,
            currency = currency,
            balance = BigDecimal.ZERO,
        )

        entityManager.persist(account)
        return account
    }

    // ------------------------------------------------------------- the posting

    private fun post(payment: PaymentEventPayload, accounts: PaymentAccounts.Found): LedgerOutcome {
        val refusal = refusalFor(payment, accounts)
        if (refusal != null) return refuse(payment, refusal)

        recordEntries(payment, accounts)
        ledgerEvents.publish(LedgerEventType.PAYMENT_COMPLETED, payment.transactionId)
        return LedgerOutcome.Applied
    }

    private fun refuse(payment: PaymentEventPayload, refusal: Refusal): LedgerOutcome {
        ledgerEvents.publish(LedgerEventType.PAYMENT_FAILED, payment.transactionId, refusal)
        return LedgerOutcome.Rejected(refusal)
    }

    private fun refusalFor(
        payment: PaymentEventPayload,
        accounts: PaymentAccounts.Found,
    ): Refusal? {
        wrongCurrency(payment, accounts)?.let { return it }

        if (accounts.debited.id == accounts.credited.id) {
            return Refusal(RejectionReason.SELF_TRANSFER)
        }

        // Only a wallet ever loses money on a debit, so this is the payer.
        if (wouldGoNegative(accounts.debited, EntryDirection.DEBIT, payment.amount)) {
            return Refusal(RejectionReason.INSUFFICIENT_FUNDS)
        }

        // Only the platform's cash ever loses money on a credit, so this is the float
        // running dry on a withdrawal. It should be impossible - what the platform
        // holds equals what it owes, so if the wallet covers it the float does too.
        // It is here so that an impossible case fails cleanly instead of blowing up
        // on the database's "balance can never be negative" rule.
        if (wouldGoNegative(accounts.credited, EntryDirection.CREDIT, payment.amount)) {
            return Refusal(RejectionReason.FUNDING_ACCOUNT_SHORT)
        }

        return null
    }

    private fun wrongCurrency(
        payment: PaymentEventPayload,
        accounts: PaymentAccounts.Found,
    ): Refusal? {
        val account = listOf(accounts.debited, accounts.credited)
            .firstOrNull { it.currency != payment.currency }
            ?: return null

        val movement = when (payment.type) {
            PaymentEventPayload.DEPOSIT -> "deposit"
            PaymentEventPayload.WITHDRAWAL -> "withdrawal"
            else -> "transfer"
        }

        val reason = when (payment.type) {
            PaymentEventPayload.WITHDRAWAL, PaymentEventPayload.DEPOSIT -> RejectionReason.ACCOUNT_CURRENCY_MISMATCH
            else -> RejectionReason.CURRENCY_MISMATCH
        }

        return Refusal(
            reason,
            "This account holds ${account.currency}, the $movement was in ${payment.currency}."
        )
    }

    private fun wouldGoNegative(
        account: Account,
        direction: EntryDirection,
        amount: BigDecimal,
    ): Boolean =
        direction != account.accountClass.normalBalance && !account.canCover(amount)

    private fun recordEntries(payment: PaymentEventPayload, accounts: PaymentAccounts.Found) {
        entries.saveAll(
            listOf(
                LedgerEntry(
                    transactionId = payment.transactionId,
                    accountId = accounts.debited.id,
                    direction = EntryDirection.DEBIT,
                    amount = payment.amount,
                    currency = payment.currency,
                ),
                LedgerEntry(
                    transactionId = payment.transactionId,
                    accountId = accounts.credited.id,
                    direction = EntryDirection.CREDIT,
                    amount = payment.amount,
                    currency = payment.currency,
                ),
            )
        )

        accounts.debited.applyEntry(EntryDirection.DEBIT, payment.amount)
        accounts.credited.applyEntry(EntryDirection.CREDIT, payment.amount)
    }

    private fun markEventProcessed(envelope: PaymentEventEnvelope) {
        processedEvents.record(envelope.eventId, envelope.transactionId)
    }
}
