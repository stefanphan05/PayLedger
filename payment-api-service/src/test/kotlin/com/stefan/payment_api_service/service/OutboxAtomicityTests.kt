package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.models.dto.TransactionRequestDTO
import com.stefan.payment_api_service.models.entity.OutboxEvent
import com.stefan.payment_api_service.models.entity.User
import com.stefan.payment_api_service.models.enum.PaymentEventType
import com.stefan.payment_api_service.repository.OutboxEventRepository
import com.stefan.payment_api_service.repository.TransactionRepository
import com.stefan.payment_api_service.repository.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * The two guarantees the outbox exists for, pinned.
 *
 * Deliberately NO Kafka container: both tests need a broker that fails exactly when
 * told to - one that accepts a record and then refuses the next - which a real broker
 * cannot be made to do on cue. That is a mock, and it needs a real Spring context and
 * a real database around it to mean anything, because what is under test is the
 * TRANSACTION BOUNDARY, not the sending.
 *
 * There is deliberately no @Transactional on this class. It would wrap each test in a
 * rolled-back transaction and both tests below would then pass no matter what the
 * production code does.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OutboxAtomicityTests @Autowired constructor(
    val transactionService: TransactionService,
    val outboxPublisher: OutboxPublisher,
    val outboxEventRepository: OutboxEventRepository,
    val transactionRepository: TransactionRepository,
    val userRepository: UserRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
    }

    @MockitoBean
    lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @MockitoBean
    lateinit var paymentEventPublisher: PaymentEventPublisher

    private lateinit var sender: User
    private lateinit var recipient: User

    @BeforeEach
    fun seedUsers() {
        sender = userRepository.save(mockUser("sender@example.com"))
        recipient = userRepository.save(mockUser("recipient@example.com"))
    }

    @AfterEach
    fun cleanUp() {
        outboxEventRepository.deleteAll()
        transactionRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `a failed outbox write rolls the transaction back`() {
        doThrow(RuntimeException("outbox insert failed"))
            .whenever(paymentEventPublisher).publish(any(), any())

        assertThrows<RuntimeException> {
            transactionService.createTransaction(
                TransactionRequestDTO(
                    amount = BigDecimal("15.90"),
                    currencyCode = "AUD",
                    recipientId = recipient.id,
                ),
                sender.id,
            )
        }

        // saveAndFlush already pushed the INSERT to the database before publish() threw,
        // so the row only disappears if the surrounding transaction actually rolled back.
        // Without @Transactional on createTransaction this is 1: a committed payment that
        // no consumer will ever hear about, which is the whole failure ADR-0004 exists
        // to prevent.
        assertEquals(0, transactionRepository.count())
        assertEquals(0, outboxEventRepository.count())
    }

    @Test
    fun `a mid-batch send failure leaves the whole batch unpublished`() {
        outboxEventRepository.saveAll(listOf(outboxRow(), outboxRow()))

        val delivered = CompletableFuture.completedFuture(mock<SendResult<String, String>>())
        val refused = CompletableFuture.failedFuture<SendResult<String, String>>(
            RuntimeException("broker down"),
        )
        whenever(kafkaTemplate.send(any(), any(), any())).thenReturn(delivered, refused)

        assertThrows<Exception> { outboxPublisher.publishBatch() }

        // BOTH rows, including the one whose send succeeded, must still be unpublished.
        //
        // This is what rollbackFor buys. CompletableFuture.get() throws
        // ExecutionException, which is CHECKED, and Spring's default rolls back on
        // unchecked exceptions only - so with a plain @Transactional the first row
        // commits as published and this assertion finds 1 instead of 2. Kotlin has no
        // checked exceptions, so nothing at the call site hints at any of that.
        val unpublished = outboxEventRepository.findAll().count { it.publishedAt == null }
        assertEquals(2, unpublished)
    }

    private fun outboxRow() = OutboxEvent(
        transactionId = UUID.randomUUID(),
        eventType = PaymentEventType.PAYMENT_INITIATED,
        payload = """{"eventId":"${UUID.randomUUID()}","eventType":"PAYMENT_INITIATED"}""",
    )

    private fun mockUser(email: String) = User(
        firstName = "Test",
        lastName = "User",
        email = email,
        password = "not-a-real-hash",
    )
}
