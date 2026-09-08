package com.stefan.payment_api_service.ledger

import com.stefan.payment_api_service.auth.model.User
import com.stefan.payment_api_service.auth.repository.UserRepository
import com.stefan.payment_api_service.ledger.consumer.LedgerEventListener
import com.stefan.payment_api_service.ledger.model.LedgerEventEnvelope
import com.stefan.payment_api_service.ledger.model.LedgerEventPayload
import com.stefan.payment_api_service.ledger.repository.ProcessedEventRepository
import com.stefan.payment_api_service.outbox.model.PaymentEventType
import com.stefan.payment_api_service.outbox.repository.OutboxEventRepository
import com.stefan.payment_api_service.transaction.model.Transaction
import com.stefan.payment_api_service.transaction.model.TransactionStatus
import com.stefan.payment_api_service.transaction.repository.TransactionRepository
import com.stefan.payment_api_service.transaction.service.TransactionService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class SettlementIntegrationTests @Autowired constructor(
    val listener: LedgerEventListener,
    val transactionService: TransactionService,
    val transactions: TransactionRepository,
    val processedEvents: ProcessedEventRepository,
    val outboxEvents: OutboxEventRepository,
    val users: UserRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
    }

    private lateinit var sender: User
    private lateinit var recipient: User

    @BeforeEach
    fun seedUsers() {
        sender = users.save(mockUser("sender@example.com"))
        recipient = users.save(mockUser("recipient@example.com"))
    }

    @AfterEach
    fun cleanUp() {
        outboxEvents.deleteAll()
        processedEvents.deleteAll()
        transactions.deleteAll()
        users.deleteAll()
    }

    @Test
    fun `a PAYMENT_COMPLETED verdict settles the transaction`() {
        val transaction = pendingTransaction()

        listener.onLedgerEvent(verdict(transaction.id, "PAYMENT_COMPLETED"))

        val settled = transactions.findById(transaction.id).orElseThrow()
        assertEquals(TransactionStatus.COMPLETED, settled.transactionStatus)
        assertNull(settled.failureReason, "a completed payment has nothing to explain")
    }

    @Test
    fun `a PAYMENT_FAILED verdict records the ledger's reason`() {
        val transaction = pendingTransaction()

        listener.onLedgerEvent(verdict(transaction.id, "PAYMENT_FAILED", "INSUFFICIENT_FUNDS"))
        val settled = transactions.findById(transaction.id).orElseThrow()
        assertEquals(TransactionStatus.FAILED, settled.transactionStatus)
        assertEquals("INSUFFICIENT_FUNDS", settled.failureReason)
    }

    @Test
    fun `a redelivered verdict settles the payment only once`() {
        val transaction = pendingTransaction()
        val record = verdict(transaction.id, "PAYMENT_COMPLETED")

        listener.onLedgerEvent(record)                           // ← 1st delivery
        assertDoesNotThrow { listener.onLedgerEvent(record) }    // ← 2nd delivery

        assertEquals(1L, processedEvents.count(), "the event id must be claimed once")
        assertEquals(TransactionStatus.COMPLETED, transactions.findById(transaction.id).orElseThrow().transactionStatus)
        assertEquals(1, statusChangedRows().size)
    }

    @Test
    fun `settling writes one status-changed event to the outbox, unpublished`() {
        val transaction = pendingTransaction()

        listener.onLedgerEvent(verdict(transaction.id, "PAYMENT_COMPLETED"))

        val row = statusChangedRows().single()
        assertEquals(transaction.id, row.transactionId)
        assertNull(row.publishedAt)
    }

    @Test
    fun `an unrecognised event type is ignored`() {
        val transaction = pendingTransaction()

        listener.onLedgerEvent(verdict(transaction.id, "PAYMENT_REFUNDED"))

        // Ignored means untouched, not just unsettled: no claim, no status change.
        assertEquals(TransactionStatus.PENDING, transactions.findById(transaction.id).orElseThrow().transactionStatus)
        assertEquals(0L, processedEvents.count())
    }

    @Test
    fun `a verdict for an unknown transaction is dropped rather than rethrown`() {
        val record = verdict(UUID.randomUUID(), "PAYMENT_COMPLETED")

        assertDoesNotThrow { listener.onLedgerEvent(record) }

        // The claim was rolled back with the rest of the transaction, so a future
        // delivery of this event id is still free to be processed.
        assertEquals(0L, processedEvents.count())
    }

    @ParameterizedTest
    @EnumSource(TransactionStatus::class)
    fun `settle drives the transaction to whichever status it is given`(status: TransactionStatus) {
        val transaction = pendingTransaction()

        transactionService.settle(envelope(transaction.id, "PAYMENT_COMPLETED"), status)

        assertEquals(status, transactions.findById(transaction.id).orElseThrow().transactionStatus)
    }

    private fun pendingTransaction(): Transaction = transactions.saveAndFlush(
        Transaction(
            amount = BigDecimal("15.90"),
            currency = "AUD",
            transactionStatus = TransactionStatus.PENDING,
            senderId = sender.id,
            recipientId = recipient.id,
        )
    )

    private fun statusChangedRows() =
        outboxEvents.findAll().filter { it.eventType == PaymentEventType.PAYMENT_STATUS_CHANGED }

    private fun envelope(
        transactionId: UUID,
        eventType: String,
        reason: String? = null,
        eventId: UUID = UUID.randomUUID(),
    ) = LedgerEventEnvelope(
        eventId = eventId,
        eventType = eventType,
        occurredAt = Instant.now(),
        transactionId = transactionId,
        payload = LedgerEventPayload(transactionId, reason),
    )

    private fun verdict(
        transactionId: UUID,
        eventType: String,
        reason: String? = null,
        eventId: UUID = UUID.randomUUID(),
    ): ConsumerRecord<String, String> {
        val reasonJson = if (reason != null) "\"$reason\"" else "null"

        val json = """
        {
          "eventId": "$eventId",
          "eventType": "$eventType",
          "occurredAt": "2026-09-08T00:00:00Z",
          "transactionId": "$transactionId",
          "payload": {
            "transactionId": "$transactionId",
            "reason": $reasonJson
          }
        }
    """.trimIndent()

        val topic = "ledger-events"
        val partition = 0
        val offset = 0L
        val key = transactionId.toString()  // Kafka key = transactionId, so all events for the same transaction land on the same partition

        return ConsumerRecord(topic, partition, offset, key, json)
    }

    private fun mockUser(email: String) = User(
        firstName = "Test",
        lastName = "User",
        email = email,
        password = "not-a-real-hash",
    )
}