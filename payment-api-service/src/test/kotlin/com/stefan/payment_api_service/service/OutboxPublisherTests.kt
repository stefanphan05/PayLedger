package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.config.PaymentEventProperties
import com.stefan.payment_api_service.models.entity.OutboxEvent
import com.stefan.payment_api_service.models.enum.PaymentEventType
import com.stefan.payment_api_service.repository.OutboxEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * The poller's send logic, in isolation.
 *
 * NOTE what this class CANNOT tell you: there is no Spring proxy here, so publishBatch
 * runs outside any transaction and nothing below exercises rollbackFor. The guarantee
 * that a half-sent batch rolls back is pinned in OutboxAtomicityTests instead.
 */
@ExtendWith(MockitoExtension::class)
class OutboxPublisherTests {
    @Mock
    lateinit var repository: OutboxEventRepository

    @Mock
    lateinit var kafkaTemplate: KafkaTemplate<String, String>

    // Real properties rather than a mock - it is a data class of defaults.
    private val properties = PaymentEventProperties()
    private lateinit var publisher: OutboxPublisher

    @BeforeEach
    fun setUp() {
        publisher = OutboxPublisher(repository, kafkaTemplate, properties)
    }

    @Test
    fun `each record is keyed by the transaction id`() {
        val event = outboxEvent()
        givenUnpublished(event)
        givenSendSucceeds()

        publisher.publishBatch()

        val topic = argumentCaptor<String>()
        val key = argumentCaptor<String>()
        val value = argumentCaptor<String>()
        verify(kafkaTemplate).send(topic.capture(), key.capture(), value.capture())

        assertEquals(properties.topic, topic.firstValue)
        // The key is what keeps a transaction's events on one partition, and therefore
        // in order for the consumer.
        assertEquals(event.transactionId.toString(), key.firstValue)
    }

    @Test
    fun `the payload column is sent unchanged`() {
        val event = outboxEvent(payload = """{"eventId":"fixed-id","eventType":"PAYMENT_INITIATED"}""")
        givenUnpublished(event)
        givenSendSucceeds()

        publisher.publishBatch()

        val value = argumentCaptor<String>()
        verify(kafkaTemplate).send(any(), any(), value.capture())
        // Serialised once at write time and resent byte for byte, so a retry carries
        // the same eventId that consumers dedupe on. Re-serialising here would mint a
        // new one and break that.
        assertEquals(event.payload, value.firstValue)
    }

    @Test
    fun `a successful send stamps publishedAt`() {
        val event = outboxEvent()
        givenUnpublished(event)
        givenSendSucceeds()

        publisher.publishBatch()

        assertNotNull(event.publishedAt)
    }

    @Test
    fun `a send failure leaves publishedAt null`() {
        val event = outboxEvent()
        givenUnpublished(event)
        whenever(kafkaTemplate.send(any(), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("broker down")))

        // .get() on a failed future throws ExecutionException wrapping the cause,
        // not the cause itself.
        assertThrows<ExecutionException> { publisher.publishBatch() }

        // The one assertion standing between us and marking an event delivered that
        // never left the process.
        assertNull(event.publishedAt)
    }

    @Test
    fun `an empty batch never touches Kafka`() {
        whenever(repository.lockUnpublishedBatch(any())).thenReturn(emptyList())

        publisher.publishBatch()

        // The poller runs every second forever; an idle one must not produce traffic.
        verify(kafkaTemplate, never()).send(any(), any(), any())
    }

    @Test
    fun `the configured batch size is what gets requested`() {
        whenever(repository.lockUnpublishedBatch(any())).thenReturn(emptyList())

        publisher.publishBatch()

        // Bounds how long one transaction holds its row locks waiting on Kafka.
        verify(repository).lockUnpublishedBatch(properties.batchSize)
    }

    private fun givenUnpublished(vararg events: OutboxEvent) {
        whenever(repository.lockUnpublishedBatch(any())).thenReturn(events.toList())
    }

    private fun givenSendSucceeds() {
        whenever(kafkaTemplate.send(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(mock<SendResult<String, String>>()))
    }

    private fun outboxEvent(
        transactionId: UUID = UUID.randomUUID(),
        eventType: PaymentEventType = PaymentEventType.PAYMENT_INITIATED,
        payload: String = """{"eventId":"e1","eventType":"PAYMENT_INITIATED"}""",
    ) = OutboxEvent(
        transactionId = transactionId,
        eventType = eventType,
        payload = payload,
    )
}
