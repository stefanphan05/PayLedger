package com.stefan.fraud_service.screening

import com.stefan.fraud_service.consumer.model.PaymentEventEnvelope
import com.stefan.fraud_service.consumer.model.PaymentEventPayload
import com.stefan.fraud_service.outbox.repository.OutboxEventRepository
import com.stefan.fraud_service.screening.repository.PaymentAttemptRepository
import com.stefan.fraud_service.screening.repository.ProcessedEventRepository
import com.stefan.fraud_service.screening.service.ScreeningService
import org.junit.jupiter.api.AfterEach
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
class FraudVerdictContractTests @Autowired constructor(
    private val screeningService: ScreeningService,
    private val attempts: PaymentAttemptRepository,
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
            FraudVerdictContractTests::class.java
                .getResource("/contract/fraud-verdict.json")!!
                .readText()
    }

    @AfterEach
    fun cleanUp() {
        attempts.deleteAll()
        processedEvents.deleteAll()
        outboxEvents.deleteAll()
    }

    @Test
    fun `a cleared verdict matches the published contract`() {
        screeningService.screen(paymentInitiated(BigDecimal("25.00")))

        val published = jsonMapper.readTree(outboxEvents.findAll().single().payload)

        assertMatchesContract(published)
        assertEquals("PAYMENT_CLEARED", published["eventType"].stringValue())
    }

    @Test
    fun `a blocked verdict matches the published contract`() {
        screeningService.screen(paymentInitiated(BigDecimal("500000.00")))

        val published = jsonMapper.readTree(outboxEvents.findAll().single().payload)

        assertMatchesContract(published)
        assertEquals("PAYMENT_BLOCKED", published["eventType"].stringValue())
    }

    @Test
    fun `the amount travels as a string`() {
        screeningService.screen(paymentInitiated(BigDecimal("1200.00")))

        val amount = jsonMapper.readTree(outboxEvents.findAll().single().payload)["payload"]["amount"]
        assertTrue(amount.isString, "amount is ${amount.javaClass.simpleName}, not a string")
    }

    // Checks the field names, not the values. Ids and times change every run.
    private fun assertMatchesContract(published: JsonNode) {
        val contract = jsonMapper.readTree(CONTRACT)

        assertEquals(
            contract.propertyNames().toSortedSet(),
            published.propertyNames().toSortedSet(),
            "fields changed - update contract/fraud-verdict.json in all three services",
        )
        assertEquals(
            contract["payload"].propertyNames().toSortedSet(),
            published["payload"].propertyNames().toSortedSet(),
            "payload fields changed - ledger-service reads these, update all three",
        )
        assertEquals(
            contract["decision"].propertyNames().toSortedSet(),
            published["decision"].propertyNames().toSortedSet(),
            "decision fields changed - update contract/fraud-verdict.json in all three",
        )
    }

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
                senderId = UUID.randomUUID(),
                recipientId = UUID.randomUUID(),
                createdAt = Instant.now(),
            ),
        )
    }
}