package com.stefan.payment_api_service.fraud

import com.stefan.payment_api_service.auth.repository.UserRepository
import com.stefan.payment_api_service.fraud.model.FraudEventEnvelope
import com.stefan.payment_api_service.fraud.model.FraudVerdict
import com.stefan.payment_api_service.ledger.repository.ProcessedEventRepository
import com.stefan.payment_api_service.outbox.model.PaymentEventType
import com.stefan.payment_api_service.outbox.service.PaymentEventPublisher
import com.stefan.payment_api_service.transaction.model.Transaction
import com.stefan.payment_api_service.transaction.model.TransactionStatus
import com.stefan.payment_api_service.transaction.repository.TransactionRepository
import com.stefan.payment_api_service.transaction.service.TransactionService
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class FraudVerdictApplicationTests {
    @Mock lateinit var repository: TransactionRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var paymentEventPublisher: PaymentEventPublisher
    @Mock lateinit var processedEvents: ProcessedEventRepository

    @InjectMocks lateinit var transactionService: TransactionService

    @Test
    fun `a hold moves a pending payment to under review`() {
        val transaction = pending()
        whenever(repository.findById(transaction.id)).thenReturn(Optional.of(transaction))
        whenever(repository.save(any<Transaction>())).thenAnswer { it.arguments[0] }

        transactionService.applyFraudVerdict(event(transaction.id), FraudVerdict.HELD)
        assertEquals(TransactionStatus.UNDER_REVIEW, transaction.transactionStatus)
        verify(paymentEventPublisher).publish(PaymentEventType.PAYMENT_STATUS_CHANGED, transaction)
    }

    @Test
    fun `a block fails the payment and records the reason`() {
        val transaction = pending()
        whenever(repository.findById(transaction.id)).thenReturn(Optional.of(transaction))
        whenever(repository.save(any<Transaction>())).thenAnswer { it.arguments[0] }

        transactionService.applyFraudVerdict(event(transaction.id), FraudVerdict.BLOCKED)

        assertEquals(TransactionStatus.FAILED, transaction.transactionStatus)
        assertEquals("FRAUD_BLOCKED", transaction.failureReason)
    }

    @Test
    fun `releasing a held payment sends it back to pending`() {
        val transaction = pending().apply { transactionStatus = TransactionStatus.UNDER_REVIEW }
        whenever(repository.findById(transaction.id)).thenReturn(Optional.of(transaction))
        whenever(repository.save(any<Transaction>())).thenAnswer { it.arguments[0] }

        transactionService.applyFraudVerdict(event(transaction.id), FraudVerdict.CLEARED)

        assertEquals(TransactionStatus.PENDING, transaction.transactionStatus)
    }

    @Test
    fun `a clearance arriving after settlement leaves the payment completed`() {
        val settled = pending().apply { transactionStatus = TransactionStatus.COMPLETED }
        whenever(repository.findById(settled.id)).thenReturn(Optional.of(settled))

        val result = transactionService.applyFraudVerdict(event(settled.id), FraudVerdict.CLEARED)

        assertNull(result, "an ignored verdict must not report a change")
        assertEquals(TransactionStatus.COMPLETED, settled.transactionStatus)
        verify(repository, never()).save(any<Transaction>())
        verify(paymentEventPublisher, never()).publish(any(), any())
    }

    @Test
    fun `a hold on an already settled payment is ignored`() {
        val settled = pending().apply { transactionStatus = TransactionStatus.COMPLETED }
        whenever(repository.findById(settled.id)).thenReturn(Optional.of(settled))

        transactionService.applyFraudVerdict(event(settled.id), FraudVerdict.HELD)

        assertEquals(TransactionStatus.COMPLETED, settled.transactionStatus)
        verify(repository, never()).save(any<Transaction>())
    }

    @Test
    fun `an ignored verdict is still marked processed`() {
        val settled = pending().apply { transactionStatus = TransactionStatus.COMPLETED }
        val event = event(settled.id)
        whenever(repository.findById(settled.id)).thenReturn(Optional.of(settled))

        transactionService.applyFraudVerdict(event, FraudVerdict.CLEARED)

        verify(processedEvents).record(event.eventId, event.transactionId)
    }
    private fun pending() = Transaction(
        amount = BigDecimal("25.00"),
        currency = "AUD",
        transactionStatus = TransactionStatus.PENDING,
        senderId = UUID.randomUUID(),
        recipientId = UUID.randomUUID(),
    )

    private fun event(transactionId: UUID) = FraudEventEnvelope(
        eventId = UUID.randomUUID(),
        eventType = "PAYMENT_CLEARED",
        occurredAt = Instant.now(),
        transactionId = transactionId,
    )
}