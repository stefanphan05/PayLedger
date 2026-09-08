package com.stefan.ledger_service.ledger

import com.stefan.ledger_service.ledger.consumer.model.PaymentEventEnvelope
import com.stefan.ledger_service.ledger.consumer.model.PaymentEventPayload
import com.stefan.ledger_service.ledger.model.Account
import com.stefan.ledger_service.ledger.model.AccountClass
import com.stefan.ledger_service.ledger.repository.AccountRepository
import com.stefan.ledger_service.ledger.repository.LedgerEntryRepository
import com.stefan.ledger_service.ledger.repository.ProcessedEventRepository
import com.stefan.ledger_service.ledger.service.LedgerService
import com.stefan.ledger_service.outbox.repository.OutboxEventRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class LedgerVerdictContractTests @Autowired constructor(
    private val ledgerService: LedgerService,
    private val accounts: AccountRepository,
    private val entries: LedgerEntryRepository,
    private val processedEvents: ProcessedEventRepository,
    private val outboxEvents: OutboxEventRepository,
    private val jsonMapper: JsonMapper,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))

        private val CONTRACT: String =
            LedgerVerdictContractTests::class.java
                .getResource("/contract/ledger-verdict.json")!!
                .readText()
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
        entries.deleteAll()
        processedEvents.deleteAll()
        outboxEvents.deleteAll()
        // Keep the funding accounts: they are only created once, by the migration.
        accounts.deleteAll(accounts.findAll().filter { it.accountClass == AccountClass.LIABILITY })
    }

    @Test
    fun `a completed verdict matches the published contract`() {
        ledgerService.apply(paymentInitiated(BigDecimal("125.50")))

        val published = jsonMapper.readTree(outboxEvents.findAll().single().payload)

        assertMatchesContract(published)
        assertEquals("PAYMENT_COMPLETED", published["eventType"].stringValue())
        assertTrue(published["payload"]["reason"].isNull, "nothing to explain when it worked")
    }

    @Test
    fun `a failed verdict carries the reason as a plain string`() {
        ledgerService.apply(paymentInitiated(BigDecimal("5000.00")))

        val published = jsonMapper.readTree(outboxEvents.findAll().single().payload)

        assertMatchesContract(published)
        assertEquals("PAYMENT_FAILED", published["eventType"].stringValue())
        // Sent as plain text, so the other service does not need to know our reasons.
        assertEquals("INSUFFICIENT_FUNDS", published["payload"]["reason"].stringValue())
    }

    // Checks the field names, not the values. Ids and times change every run.
    private fun assertMatchesContract(published: JsonNode) {
        val contract = jsonMapper.readTree(CONTRACT)

        assertEquals(
            contract.propertyNames().toSortedSet(),
            published.propertyNames().toSortedSet(),
            "fields changed - update contract/ledger-verdict.json in both services",
        )
        assertEquals(
            contract["payload"].propertyNames().toSortedSet(),
            published["payload"].propertyNames().toSortedSet(),
            "payload fields changed - update contract/ledger-verdict.json in both services",
        )
    }

    private fun wallet(balance: String) = Account(
        id = UUID.randomUUID(),
        accountClass = AccountClass.LIABILITY,
        currency = "AUD",
        balance = BigDecimal(balance),
    )

    private fun paymentInitiated(amount: BigDecimal): PaymentEventEnvelope {
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
                senderId = sender.id,
                recipientId = recipient.id,
            ),
        )
    }
}