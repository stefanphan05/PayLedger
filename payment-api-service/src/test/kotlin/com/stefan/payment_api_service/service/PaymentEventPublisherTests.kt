package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.models.entity.OutboxEvent
import com.stefan.payment_api_service.models.entity.Transaction
import com.stefan.payment_api_service.models.enum.PaymentEventType
import com.stefan.payment_api_service.models.enum.TransactionStatus
import com.stefan.payment_api_service.repository.OutboxEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

/**
 * The outbox WRITER, in isolation. There is no KafkaTemplate anywhere in this class,
 * and that absence is the point: publish() must not touch a broker.
 */
@ExtendWith(MockitoExtension::class)
class PaymentEventPublisherTests {
    @Mock
    lateinit var repository: OutboxEventRepository

    // A bare mapper, matching IdempotencyServiceTests. Safe here because every
    // assertion below is driven by the DTO's own annotations rather than mapper config.
    // Timestamp FORMAT is deliberately NOT asserted in this class - a bare mapper writes
    // epoch numbers where Boot's configured one writes ISO strings, so that assertion
    // belongs in OutboxIntegrationTests where the real injected mapper is in play.
    private val jsonMapper: JsonMapper = JsonMapper.builder().build()
    private lateinit var publisher: PaymentEventPublisher

    @BeforeEach
    fun setUp() {
        publisher = PaymentEventPublisher(repository, jsonMapper)
    }

    @Test
    fun `publish writes one row carrying the transaction id and event type`() {
        val transaction = mockTransaction()

        publisher.publish(PaymentEventType.PAYMENT_INITIATED, transaction)

        val row = captureSavedRow()
        assertEquals(transaction.id, row.transactionId)
        assertEquals(PaymentEventType.PAYMENT_INITIATED, row.eventType)
        // Nothing is delivered at write time - that is the poller's job.
        assertNull(row.publishedAt)
    }

    @Test
    fun `the stored payload is the serialised envelope`() {
        val transaction = mockTransaction(currency = "USD", transactionStatus = TransactionStatus.PENDING)

        publisher.publish(PaymentEventType.PAYMENT_STATUS_CHANGED, transaction)

        val payload = jsonMapper.readTree(captureSavedRow().payload)
        // The enum CONSTANT NAME is the public contract consumers match on, so it is
        // asserted as a literal string. Renaming the enum must break this test.
        assertEquals("PAYMENT_STATUS_CHANGED", payload["eventType"].stringValue())
        assertEquals(transaction.id.toString(), payload["transactionId"].stringValue())

        // A full snapshot, so a consumer never has to call back into this service.
        val body = payload["payload"]
        assertEquals(transaction.id.toString(), body["transactionId"].stringValue())
        assertEquals("USD", body["currency"].stringValue())
        assertEquals("PENDING", body["status"].stringValue())
        assertEquals(transaction.senderId.toString(), body["senderId"].stringValue())
        assertEquals(transaction.recipientId.toString(), body["recipientId"].stringValue())
    }

    @Test
    fun `amount is written as a string, not a number`() {
        val transaction = mockTransaction(amount = BigDecimal("15.90"))

        publisher.publish(PaymentEventType.PAYMENT_INITIATED, transaction)

        val payload = captureSavedRow().payload
        // Money in a JSON float is how cents go missing: a consumer reading this as a
        // double turns 15.90 into 15.9. The raw token must carry quotes.
        assertTrue(
            payload.contains("\"amount\":\"15.90\""),
            "amount must serialise as the string \"15.90\", got: $payload",
        )
    }

    @Test
    fun `each event gets its own eventId for consumers to dedupe on`() {
        val transaction = mockTransaction()

        publisher.publish(PaymentEventType.PAYMENT_INITIATED, transaction)
        publisher.publish(PaymentEventType.PAYMENT_STATUS_CHANGED, transaction)

        val captor = argumentCaptor<OutboxEvent>()
        verify(repository, times(2)).save(captor.capture())

        val first = jsonMapper.readTree(captor.firstValue.payload)["eventId"].stringValue()
        val second = jsonMapper.readTree(captor.secondValue.payload)["eventId"].stringValue()
        assertTrue(first != second, "two events for one transaction must not share an eventId")
    }

    @Test
    fun `publish lets a repository failure propagate`() {
        whenever(repository.save(any<OutboxEvent>())).thenThrow(RuntimeException("outbox insert failed"))

        // The direct-publish version this replaced swallowed everything so a Kafka
        // outage could not fail a payment. Kafka is no longer on this path, and a
        // failed local insert MUST roll the payment back rather than be logged away.
        assertThrows<RuntimeException> {
            publisher.publish(PaymentEventType.PAYMENT_INITIATED, mockTransaction())
        }
    }

    private fun captureSavedRow(): OutboxEvent {
        val captor = argumentCaptor<OutboxEvent>()
        verify(repository).save(captor.capture())
        return captor.firstValue
    }

    private fun mockTransaction(
        amount: BigDecimal = BigDecimal("15.90"),
        currency: String = "AUD",
        transactionStatus: TransactionStatus = TransactionStatus.PENDING,
    ) = Transaction(
        amount = amount,
        currency = currency,
        transactionStatus = transactionStatus,
        senderId = UUID.randomUUID(),
        recipientId = UUID.randomUUID(),
    )
}
