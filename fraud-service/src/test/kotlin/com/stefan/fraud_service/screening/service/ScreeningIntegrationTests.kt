package com.stefan.fraud_service.screening.service

import com.stefan.fraud_service.consumer.model.PaymentEventEnvelope
import com.stefan.fraud_service.consumer.model.PaymentEventPayload
import com.stefan.fraud_service.outbox.model.FraudEventType
import com.stefan.fraud_service.outbox.repository.OutboxEventRepository
import com.stefan.fraud_service.screening.model.FraudAction
import com.stefan.fraud_service.screening.repository.PaymentAttemptRepository
import com.stefan.fraud_service.screening.repository.ProcessedEventRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ScreeningIntegrationTests @Autowired constructor(
    val screeningService: ScreeningService,
    val attempts: PaymentAttemptRepository,
    val processedEvents: ProcessedEventRepository,
    val outboxEvents: OutboxEventRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
    }

    @AfterEach
    fun cleanup() {
        attempts.deleteAll()
        processedEvents.deleteAll()
        outboxEvents.deleteAll()
    }

    @Test
    fun `an valid payment is allowed and cleared`() {
        val event = paymentInitiated(BigDecimal("25.00"))

        val decision = screeningService.screen(event)

        assertEquals(FraudAction.ALLOW, decision.action)
        assertEquals(0, decision.score)
        assertTrue(decision.triggeredRules.isEmpty())

        val published = outboxEvents.findAll().single()
        assertEquals(FraudEventType.PAYMENT_CLEARED, published.eventType)
        assertEquals(null, published.publishedAt)
    }

    @Test
    fun `screening records the attempt so later payments can be measured against it`() {
        val event = paymentInitiated(BigDecimal("25.00"))

        screeningService.screen(event)

        val attempt = attempts.findById(event.transactionId).orElseThrow()
        assertEquals(FraudAction.ALLOW, attempt.decision)
        assertEquals(event.payload.senderId, attempt.senderId)
        assertEquals(0, BigDecimal("25.00").compareTo(attempt.amount))
    }

    @Test
    fun `a huge payment to a stranger is blocked`() {
        // AMOUNT_CEILING (40) + NEW_RECIPIENT_LARGE (30) = 70, exactly the threshold
        val decision = screeningService.screen(paymentInitiated(BigDecimal("500000.00")))
        assertEquals(FraudAction.BLOCK, decision.action)
        assertEquals(70, decision.score)
        assertEquals(
            setOf("AMOUNT_CEILING", "NEW_RECIPIENT_LARGE"),
            decision.triggeredRules.map { it.rule }.toSet(),
        )
        assertEquals(FraudEventType.PAYMENT_BLOCKED, outboxEvents.findAll().single().eventType)
    }

    @Test
    fun `a burst from one sender trips velocity`() {
        val sender = UUID.randomUUID()
        val recipient = UUID.randomUUID()

        repeat(5) { screeningService.screen(paymentInitiated(BigDecimal("10.00"), sender, recipient)) }
        val sixth = screeningService.screen(paymentInitiated(BigDecimal("10.00"), sender, recipient))

        assertTrue(sixth.triggeredRules.any { it.rule == "VELOCITY" }, "was ${sixth.triggeredRules}")
        assertEquals(FraudAction.REVIEW, sixth.action)
        assertEquals(FraudEventType.PAYMENT_HELD, outboxEvents.findAll().last().eventType)
    }

    @Test
    fun `paying many different people quickly trips fan out`() {
        val sender = UUID.randomUUID()

        repeat(5) { screeningService.screen(paymentInitiated(BigDecimal("10.00"), sender, UUID.randomUUID())) }
        val next = screeningService.screen(paymentInitiated(BigDecimal("10.00"), sender, UUID.randomUUID()))

        assertTrue(next.triggeredRules.any { it.rule == "FAN_OUT" }, "was ${next.triggeredRules}")
    }

    @Test
    fun `a redelivered event does not screen twice`() {
        val event = paymentInitiated(BigDecimal("25.00"))
        screeningService.screen(event)

        assertThrows<DataIntegrityViolationException> { screeningService.screen(event) }
        assertEquals(1L, processedEvents.count())
        assertEquals(1L, attempts.count())
        assertEquals(1L, outboxEvents.count(), "the replay must not queue a second verdict")
    }

    private fun paymentInitiated(
        amount: BigDecimal,
        senderId: UUID = UUID.randomUUID(),
        recipientId: UUID = UUID.randomUUID(),
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
                createdAt = Instant.now(),
            ),
        )
    }
}