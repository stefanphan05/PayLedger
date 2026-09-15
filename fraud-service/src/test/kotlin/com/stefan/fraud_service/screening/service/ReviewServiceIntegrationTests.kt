package com.stefan.fraud_service.screening.service

import com.stefan.fraud_service.consumer.model.PaymentEventEnvelope
import com.stefan.fraud_service.consumer.model.PaymentEventPayload
import com.stefan.fraud_service.exception.DecisionNotFoundException
import com.stefan.fraud_service.exception.ReviewConflictException
import com.stefan.fraud_service.outbox.model.FraudEventType
import com.stefan.fraud_service.outbox.repository.OutboxEventRepository
import com.stefan.fraud_service.screening.model.FraudAction
import com.stefan.fraud_service.screening.repository.PaymentAttemptRepository
import com.stefan.fraud_service.screening.repository.ProcessedEventRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ReviewServiceIntegrationTests @Autowired constructor(
    val screeningService: ScreeningService,
    val reviewService: ReviewService,
    val attempts: PaymentAttemptRepository,
    val processedEvents: ProcessedEventRepository,
    val outboxEvents: OutboxEventRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))

        const val ADMIN = "admin-user-id"
    }

    @AfterEach
    fun cleanUp() {
        attempts.deleteAll()
        processedEvents.deleteAll()
        outboxEvents.deleteAll()
    }

    @Test
    fun `releasing a held payment publishes a cleared event`() {
        val held = heldPayment()

        reviewService.release(held, ADMIN)

        val published = outboxEvents.findAll().last()
        print(published.payload)
        assertEquals(FraudEventType.PAYMENT_CLEARED, published.eventType)
        assertTrue(published.payload.contains(ADMIN), "the human who decided is not recorded")
    }

    @Test
    fun `rejecting a held payment publishes a blocked event`() {
        val held = heldPayment()

        reviewService.reject(held, ADMIN)

        assertEquals(FraudEventType.PAYMENT_BLOCKED, outboxEvents.findAll().last().eventType)
        assertEquals(FraudAction.BLOCK, attempts.findById(held).orElseThrow().overriddenTo)
    }

    @Test
    fun `a payment cannot be released twice`() {
        val held = heldPayment()
        reviewService.release(held, ADMIN)
        val afterFirst = outboxEvents.count()

        assertThrows<ReviewConflictException> { reviewService.release(held, ADMIN) }
        assertEquals(afterFirst, outboxEvents.count(), "the second release queued another event")
    }

    @Test
    fun `an allowed payment cannot be released`() {
        val allowed = screenedPayment(BigDecimal("25.00")).transactionId
        assertThrows<ReviewConflictException> { reviewService.release(allowed, ADMIN) }
    }

    @Test
    fun `releasing something never screened is not found`() {
        assertThrows<DecisionNotFoundException> { reviewService.release(UUID.randomUUID(), ADMIN) }
    }

    @Test
    fun `the released event carries the original correlation id`() {
        val held = heldPayment()
        val original = attempts.findById(held).orElseThrow().correlationId

        reviewService.release(held, ADMIN)

        assertEquals(original, outboxEvents.findAll().last().correlationId)
    }

    @Test
    fun `an overturned decision records who decided and when`() {
        val held = heldPayment()

        reviewService.release(held, ADMIN)

        val attempt = attempts.findById(held).orElseThrow()
        assertNotNull(attempt.overriddenAt)
        assertEquals(FraudAction.ALLOW, attempt.overriddenTo)
        assertEquals(FraudAction.REVIEW, attempt.decision)
    }

    /** Six payments from one sender: the sixth trips velocity and is held. */
    private fun heldPayment(): UUID {
        val sender = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        repeat(5) { screenedPayment(BigDecimal("10.00"), sender, recipient) }
        return screenedPayment(BigDecimal("10.00"), sender, recipient).transactionId
    }

    private fun screenedPayment(
        amount: BigDecimal,
        senderId: UUID = UUID.randomUUID(),
        recipientId: UUID = UUID.randomUUID(),
    ): PaymentEventEnvelope {
        val transactionId = UUID.randomUUID()
        val event = PaymentEventEnvelope(
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
                createdAt = Instant.now(),
            ),
        )
        screeningService.screen(event)
        return event
    }
}