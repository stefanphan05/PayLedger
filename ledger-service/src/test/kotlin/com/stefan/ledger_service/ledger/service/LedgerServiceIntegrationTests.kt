package com.stefan.ledger_service.ledger.service

import com.stefan.ledger_service.ledger.consumer.model.PaymentEventEnvelope
import com.stefan.ledger_service.ledger.consumer.model.PaymentEventPayload
import com.stefan.ledger_service.ledger.model.Account
import com.stefan.ledger_service.ledger.model.AccountClass
import com.stefan.ledger_service.ledger.model.EntryDirection
import com.stefan.ledger_service.ledger.model.RejectionReason
import com.stefan.ledger_service.ledger.repository.AccountRepository
import com.stefan.ledger_service.ledger.repository.LedgerEntryRepository
import com.stefan.ledger_service.ledger.repository.ProcessedEventRepository
import com.stefan.ledger_service.outbox.model.LedgerEventType
import com.stefan.ledger_service.outbox.repository.OutboxEventRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The apply path against a real Postgres.
 *
 * No @Transactional on this class on purpose. Every guarantee here depends on things
 * COMMITTING, and a test-managed rollback would hide precisely what is being asserted.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class LedgerServiceIntegrationTests @Autowired constructor(
    val ledgerService: LedgerService,
    val accounts: AccountRepository,
    val entries: LedgerEntryRepository,
    val processedEvents: ProcessedEventRepository,
    val outboxEvents: OutboxEventRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
    }

    private lateinit var sender: Account
    private lateinit var recipient: Account

    @BeforeEach
    fun seedWallets() {
        sender = accounts.save(wallet("1000.00"))
        recipient = accounts.save(wallet("0.00"))
    }

    @AfterEach
    fun cleanUp() {
        // Entries first: they hold a foreign key to accounts.
        entries.deleteAll()
        processedEvents.deleteAll()
        outboxEvents.deleteAll()
        // Only wallets. The ASSET rows come from V2 and must survive, because Flyway
        // will not run again for the next test in this container.
        accounts.deleteAll(accounts.findAll().filter { it.accountClass == AccountClass.LIABILITY })
    }

    @Test
    fun `applying a transfer writes a balanced pair and moves both balances`() {
        val event = paymentInitiated(BigDecimal("125.50"))

        val outcome = ledgerService.apply(event)

        assertEquals(LedgerOutcome.Applied, outcome)
        assertBalance("874.50", sender.id)
        assertBalance("125.50", recipient.id)

        val written = entries.findAll().filter { it.transactionId == event.transactionId }
        assertEquals(2, written.size)

        val debits = written.filter { it.direction == EntryDirection.DEBIT }
            .fold(BigDecimal.ZERO) { total, entry -> total + entry.amount }
        val credits = written.filter { it.direction == EntryDirection.CREDIT }
            .fold(BigDecimal.ZERO) { total, entry -> total + entry.amount }
        assertEquals(0, debits.compareTo(credits), "debits $debits must equal credits $credits")
    }

    @Test
    fun `a completed payment records its verdict in the outbox, unpublished`() {
        val event = paymentInitiated(BigDecimal("10.00"))

        ledgerService.apply(event)

        val verdict = outboxEvents.findAll().single()
        assertEquals(LedgerEventType.PAYMENT_COMPLETED, verdict.eventType)
        assertEquals(event.transactionId, verdict.transactionId)
        // Written, not sent. Nothing reaches the broker on the apply path.
        assertEquals(null, verdict.publishedAt)
    }

    /**
     * THE test for this feature. Kafka delivers at least once, so reapplying the same
     * event must not move money a second time.
     */
    @Test
    fun `a redelivered event does not apply twice`() {
        val event = paymentInitiated(BigDecimal("100.00"))
        ledgerService.apply(event)

        assertThrows<DataIntegrityViolationException> { ledgerService.apply(event) }

        assertBalance("900.00", sender.id)
        assertBalance("100.00", recipient.id)
        assertEquals(1L, processedEvents.count())
        assertEquals(2L, entries.count())
        assertEquals(1L, outboxEvents.count(), "the replay must not queue a second verdict")
    }

    @Test
    fun `an unaffordable payment writes no entries and a FAILED verdict`() {
        val event = paymentInitiated(BigDecimal("5000.00"))

        val outcome = ledgerService.apply(event)

        assertEquals(LedgerOutcome.Rejected(RejectionReason.INSUFFICIENT_FUNDS), outcome)
        assertEquals(0L, entries.count())
        assertBalance("1000.00", sender.id)

        val verdict = outboxEvents.findAll().single()
        assertEquals(LedgerEventType.PAYMENT_FAILED, verdict.eventType)
        assertTrue(verdict.payload.contains("INSUFFICIENT_FUNDS"), "was ${verdict.payload}")

        // Committed, not rolled back. A rejection is final: replaying it must not
        // re-evaluate the payment later, when the account may have been funded.
        assertEquals(1L, processedEvents.count())
    }

    @Test
    fun `paying yourself is refused`() {
        val event = paymentInitiated(BigDecimal("10.00"), recipientId = sender.id)

        val outcome = ledgerService.apply(event)

        assertEquals(LedgerOutcome.Rejected(RejectionReason.SELF_TRANSFER), outcome)
        assertEquals(0L, entries.count())
        assertBalance("1000.00", sender.id)
    }

    @Test
    fun `an account the ledger has never seen is created at zero and refused`() {
        val stranger = UUID.randomUUID()
        val event = paymentInitiated(BigDecimal("10.00"), senderId = stranger)

        val outcome = ledgerService.apply(event)

        assertEquals(LedgerOutcome.Rejected(RejectionReason.INSUFFICIENT_FUNDS), outcome)
        val created = accounts.findById(stranger).orElseThrow()
        assertEquals(AccountClass.LIABILITY, created.accountClass)
        assertBalance("0.00", stranger)
    }

    private fun wallet(balance: String) = Account(
        id = UUID.randomUUID(),
        accountClass = AccountClass.LIABILITY,
        currency = "AUD",
        balance = BigDecimal(balance),
    )

    private fun paymentInitiated(
        amount: BigDecimal,
        senderId: UUID = sender.id,
        recipientId: UUID = recipient.id,
    ): PaymentEventEnvelope {
        val transactionId = UUID.randomUUID()
        return PaymentEventEnvelope(
            eventId = UUID.randomUUID(),
            eventType = "PAYMENT_INITIATED",
            occurredAt = Instant.now(),
            transactionId = transactionId,
            payload = PaymentEventPayload(
                transactionId = transactionId,
                amount = amount,
                currency = "AUD",
                senderId = senderId,
                recipientId = recipientId,
            ),
        )
    }

    private fun assertBalance(expected: String, accountId: UUID) {
        val actual = accounts.findById(accountId).orElseThrow().balance
        assertEquals(0, BigDecimal(expected).compareTo(actual), "was $actual")
    }
}
