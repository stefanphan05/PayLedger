package com.stefan.payment_api_service.ledger

import com.stefan.payment_api_service.auth.model.User
import com.stefan.payment_api_service.auth.repository.UserRepository
import com.stefan.payment_api_service.ledger.repository.ProcessedEventRepository
import com.stefan.payment_api_service.outbox.repository.OutboxEventRepository
import com.stefan.payment_api_service.transaction.model.Transaction
import com.stefan.payment_api_service.transaction.model.TransactionStatus
import com.stefan.payment_api_service.transaction.repository.TransactionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.fail

/**
 * Proves this service can read the messages ledger-service sends.
 *
 * The message goes through a real Kafka, so the topic name and settings are tested too.
 * ledger-service has a matching test proving it still sends this shape. Both read the
 * same contract/ledger-verdict.json.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
// Listeners are off in tests. This class needs one running.
@TestPropertySource(properties = ["spring.kafka.listener.auto-startup=true"])
class LedgerVerdictContractTests @Autowired constructor(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val transactions: TransactionRepository,
    private val processedEvents: ProcessedEventRepository,
    private val outboxEvents: OutboxEventRepository,
    private val users: UserRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))

        @Container
        @ServiceConnection
        @JvmStatic
        val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"))

        private val CONTRACT: String = LedgerVerdictContractTests::class.java
            .getResource("/contract/ledger-verdict.json")!!
            .readText()
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
    fun `a PAYMENT_COMPLETED verdict off the topic settles the payment`() {
        val transaction = pendingTransaction()

        publishVerdict(transaction.id, "PAYMENT_COMPLETED")

        val settled = awaitSettlement(transaction.id)
        assertEquals(TransactionStatus.COMPLETED, settled.transactionStatus)
        assertNull(settled.failureReason)
    }

    @Test
    fun `a PAYMENT_FAILED verdict off the topic records the ledger's reason`() {
        val transaction = pendingTransaction()

        publishVerdict(transaction.id, "PAYMENT_FAILED", reason = "INSUFFICIENT_FUNDS")

        val settled = awaitSettlement(transaction.id)
        assertEquals(TransactionStatus.FAILED, settled.transactionStatus)
        assertEquals("INSUFFICIENT_FUNDS", settled.failureReason)
    }

    // Kafka can deliver the same message twice. The payment must only settle once.
    @Test
    fun `the same verdict delivered twice settles the payment once`() {
        val transaction = pendingTransaction()
        val eventId = UUID.randomUUID()

        publishVerdict(transaction.id, "PAYMENT_COMPLETED", eventId = eventId)
        publishVerdict(transaction.id, "PAYMENT_COMPLETED", eventId = eventId)

        awaitSettlement(transaction.id)
        // Wait for the second copy to arrive and be rejected, or we check too early.
        Thread.sleep(2_000)

        assertEquals(1L, processedEvents.count())
        assertEquals(1L, outboxEvents.count(), "the repeat must not raise a second event")
    }

    private fun publishVerdict(
        transactionId: UUID,
        eventType: String,
        reason: String? = null,
        eventId: UUID = UUID.randomUUID(),
    ) {
        val json = CONTRACT
            .replace("@eventId@", eventId.toString())
            .replace("@eventType@", eventType)
            .replace("@transactionId@", transactionId.toString())
            // Quotes included, so no reason becomes null rather than "null".
            .replace("\"@reason@\"", reason?.let { "\"$it\"" } ?: "null")

        kafkaTemplate.send("ledger-events", transactionId.toString(), json).get()
    }

    // The payment settles in the background, so wait for it rather than checking once.
    private fun awaitSettlement(id: UUID, timeout: Duration = Duration.ofSeconds(30)): Transaction {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now() < deadline) {
            val transaction = transactions.findById(id).orElseThrow()
            if (transaction.transactionStatus != TransactionStatus.PENDING) return transaction
            Thread.sleep(200)
        }

        fail("transaction $id never left PENDING within $timeout")
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

    private fun mockUser(email: String) = User(
        firstName = "Test",
        lastName = "User",
        email = email,
        password = "not-a-real-hash",
    )
}