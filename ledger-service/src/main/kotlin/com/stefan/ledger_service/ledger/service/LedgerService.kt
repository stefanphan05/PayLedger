package com.stefan.ledger_service.ledger.service

import com.stefan.ledger_service.ledger.consumer.model.PaymentEventEnvelope
import com.stefan.ledger_service.ledger.consumer.model.PaymentEventPayload
import com.stefan.ledger_service.ledger.model.Account
import com.stefan.ledger_service.ledger.model.AccountClass
import com.stefan.ledger_service.ledger.model.EntryDirection
import com.stefan.ledger_service.ledger.model.LedgerEntry
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
        val sender = walletFor(payment.senderId, payment.currency)
        val recipient = walletFor(payment.recipientId, payment.currency)

        // validate the transaction
        val rejection = validateTransfer(sender, recipient, payment)
        if (rejection != null) {
            ledgerEvents.publish(LedgerEventType.PAYMENT_FAILED, payment.transactionId, rejection)
            return LedgerOutcome.Rejected(rejection)
        }

        recordTransfer(sender, recipient, payment)
        ledgerEvents.publish(LedgerEventType.PAYMENT_COMPLETED, payment.transactionId)
        return LedgerOutcome.Applied
    }

    private fun markEventProcessed(envelope: PaymentEventEnvelope) {
        processedEvents.record(envelope.eventId, envelope.transactionId)
    }

    private fun validateTransfer(
        sender: Account,
        recipient: Account,
        payment: PaymentEventPayload
    ): RejectionReason? {
        if (sender.currency != payment.currency || recipient.currency != payment.currency) {
            return RejectionReason.CURRENCY_MISMATCH
        }
        if (sender.id == recipient.id) {
            return RejectionReason.SELF_TRANSFER
        }
        if (!sender.canCover(payment.amount)) {
            return RejectionReason.INSUFFICIENT_FUNDS
        }

        return null
    }

    private fun recordTransfer(
        sender: Account,
        recipient: Account,
        payment: PaymentEventPayload
    ) {
        entries.saveAll(
            listOf(
                LedgerEntry(
                    transactionId = payment.transactionId,
                    accountId = sender.id,
                    direction = EntryDirection.DEBIT,
                    amount = payment.amount,
                    currency = payment.currency,
                ),
                LedgerEntry(
                    transactionId = payment.transactionId,
                    accountId = recipient.id,
                    direction = EntryDirection.CREDIT,
                    amount = payment.amount,
                    currency = payment.currency,
                ),
            )
        )

        sender.applyEntry(
            EntryDirection.DEBIT,
            payment.amount
        )

        recipient.applyEntry(
            EntryDirection.CREDIT,
            payment.amount
        )
    }

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
}