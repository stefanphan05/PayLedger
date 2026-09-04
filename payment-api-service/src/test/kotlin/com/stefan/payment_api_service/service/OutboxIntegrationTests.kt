package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.models.dto.TransactionRequestDTO
import com.stefan.payment_api_service.models.entity.User
import com.stefan.payment_api_service.models.enum.PaymentEventType
import com.stefan.payment_api_service.models.enum.TransactionStatus
import com.stefan.payment_api_service.repository.OutboxEventRepository
import com.stefan.payment_api_service.repository.TransactionRepository
import com.stefan.payment_api_service.repository.UserRepository
import com.stefan.payment_api_service.security.UserSecurity
import com.stefan.payment_api_service.service.IdempotencyService.Companion.HEADER_IDEMPOTENCY_KEY
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The outbox end to end: a transaction is saved, a row is written, the poller drains it,
 * and the bytes that land on the topic are the contract consumers depend on.
 *
 * No @Transactional on this class - the outbox only works because things COMMIT, and a
 * test-managed rollback would hide that.
 *
 * OutboxPoller itself does not exist here: polling-enabled is false under the test
 * profile, so @ConditionalOnProperty excludes the bean. publishBatch() is called
 * directly instead, which is what the poller would have called and is deterministic
 * rather than waiting for a tick.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class OutboxIntegrationTests @Autowired constructor(
    val mockMvc: MockMvc,
    val transactionService: TransactionService,
    val outboxPublisher: OutboxPublisher,
    val outboxEventRepository: OutboxEventRepository,
    val transactionRepository: TransactionRepository,
    val userRepository: UserRepository,
    val jsonMapper: JsonMapper,
    val redis: StringRedisTemplate,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))

        // org.testcontainers.kafka.KafkaContainer, NOT org.testcontainers.containers.
        // Both exist in the same jar and the IDE offers the deprecated one first, but
        // Boot's ApacheKafkaContainerConnectionDetailsFactory is typed on this one - so
        // the other import gives a container that starts fine and a @ServiceConnection
        // that silently does nothing.
        @Container
        @ServiceConnection
        @JvmStatic
        val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"))

        @Container
        @ServiceConnection("redis")
        @JvmStatic
        val redisContainer: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private lateinit var sender: User
    private lateinit var recipient: User

    @BeforeEach
    fun seedUsers() {
        sender = userRepository.save(mockUser("sender@example.com"))
        recipient = userRepository.save(mockUser("recipient@example.com"))
    }

    @AfterEach
    fun cleanUp() {
        // outbox first - nothing else clears it, and rows carry between tests otherwise
        outboxEventRepository.deleteAll()
        transactionRepository.deleteAll()
        userRepository.deleteAll()
        redis.connectionFactory?.getConnection()?.serverCommands()?.flushAll()
    }

    @Test
    fun `createTransaction writes exactly one unpublished outbox row`() {
        val transaction = createTransaction()

        val rows = outboxEventRepository.findAll()
        assertEquals(1, rows.size)
        assertEquals(transaction.id, rows[0].transactionId)
        assertEquals(PaymentEventType.PAYMENT_INITIATED, rows[0].eventType)
        // Written, not sent. Nothing reaches the broker on the request path.
        assertNull(rows[0].publishedAt)
    }

    @Test
    fun `updateTransactionStatus adds a second row for the same transaction`() {
        val transaction = createTransaction()

        transactionService.updateTransactionStatus(transaction.id, TransactionStatus.COMPLETED)

        val rows = outboxEventRepository.findAll().sortedBy { it.id }
        assertEquals(2, rows.size)
        assertEquals(PaymentEventType.PAYMENT_INITIATED, rows[0].eventType)
        assertEquals(PaymentEventType.PAYMENT_STATUS_CHANGED, rows[1].eventType)
        // Same transaction, so both carry the same key and stay ordered on one partition.
        assertTrue(rows.all { it.transactionId == transaction.id })
    }

    @Test
    fun `publishBatch delivers every unsent row and stamps published_at`() {
        val transaction = createTransaction()

        outboxPublisher.publishBatch()

        val row = outboxEventRepository.findAll().single()
        assertNotNull(row.publishedAt)
        assertEquals(1, recordsFor(transaction.id).size)
    }

    @Test
    fun `publishBatch called twice delivers each event only once`() {
        val transaction = createTransaction()

        outboxPublisher.publishBatch()
        outboxPublisher.publishBatch()

        // The second pass must find nothing: published_at IS NULL is what stops a
        // delivered event going out again on every subsequent tick, forever.
        assertEquals(1, recordsFor(transaction.id).size)
    }

    @Test
    fun `the record key is the transaction id`() {
        val transaction = createTransaction()

        outboxPublisher.publishBatch()

        val record = recordsFor(transaction.id).single()
        assertEquals(transaction.id.toString(), record.key())
    }

    @Test
    fun `the published envelope carries the full transaction snapshot`() {
        val transaction = createTransaction(amount = BigDecimal("15.90"), currency = "AUD")

        outboxPublisher.publishBatch()

        val envelope = jsonMapper.readTree(recordsFor(transaction.id).single().value())

        // The enum constant name IS the contract consumers match on.
        assertEquals("PAYMENT_INITIATED", envelope["eventType"].stringValue())
        assertEquals(transaction.id.toString(), envelope["transactionId"].stringValue())

        val payload = envelope["payload"]
        assertEquals(transaction.id.toString(), payload["transactionId"].stringValue())
        assertEquals("AUD", payload["currency"].stringValue())
        assertEquals("PENDING", payload["status"].stringValue())
        assertEquals(sender.id.toString(), payload["senderId"].stringValue())
        assertEquals(recipient.id.toString(), payload["recipientId"].stringValue())

        // Money as a string, never a float - stringValue() throws on a numeric node,
        // so this fails loudly if the @JsonFormat annotation is ever dropped.
        assertEquals("15.90", payload["amount"].stringValue())

        // ISO-8601, not epoch numbers. This is what catches someone building a bare
        // JsonMapper.builder().build() somewhere instead of injecting Boot's bean:
        // a bare Jackson mapper writes timestamps as decimal epoch seconds.
        assertIsoInstant(envelope["occurredAt"], "occurredAt")
        // Also the guard on saveAndFlush: with a plain save() the insert is still queued
        // when the payload is built, @CreationTimestamp has not fired, and this is null.
        assertIsoInstant(payload["createdAt"], "payload.createdAt")
    }

    @Test
    fun `a replayed idempotent retry does not publish a second event`() {
        val key = "outbox-replay-01"

        postTransaction(key).andExpect(status().isCreated)
        postTransaction(key).andExpect(status().isCreated)

        // The publish lives inside createTransaction, which lives inside
        // idempotencyService.execute { }. A replayed retry never re-enters that block,
        // so it cannot mint a second event. Moving the publish out to the controller
        // would break exactly this.
        assertEquals(1, transactionRepository.count())
        assertEquals(1, outboxEventRepository.count())
    }

    private fun createTransaction(
        amount: BigDecimal = BigDecimal("15.90"),
        currency: String = "AUD",
    ) = transactionService.createTransaction(
        TransactionRequestDTO(amount = amount, currencyCode = currency, recipientId = recipient.id),
        sender.id,
    )

    private fun postTransaction(key: String) = mockMvc.perform(
        post("/transactions")
            .with(user(principal()))
            .header(HEADER_IDEMPOTENCY_KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"amount":"15.90","currencyCode":"AUD","recipientId":"${recipient.id}"}""")
    )

    /**
     * Records for one transaction only.
     *
     * The topic is not cleaned between tests - it is a log, and the database cleanup in
     * @AfterEach cannot touch it - so every assertion filters by key rather than
     * counting everything on the topic.
     */
    private fun recordsFor(transactionId: UUID): List<ConsumerRecord<String, String>> {
        val props = KafkaTestUtils.consumerProps(
            kafka.bootstrapServers,
            "outbox-tests-${UUID.randomUUID()}",   // fresh group, so always from the start
            false,
        )
        // consumerProps sets the KEY deserializer to IntegerDeserializer by default.
        // Our keys are UUID strings, and leaving this alone produces a
        // SerializationException that reads like a broker fault.
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java

        return KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf("payment-events"))
            KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10))
                .records("payment-events")
                .filter { it.key() == transactionId.toString() }
        }
    }

    private fun assertIsoInstant(node: JsonNode?, field: String) {
        val value = node?.stringValue()
            ?: throw AssertionError("$field is missing or null - expected an ISO-8601 string")
        try {
            Instant.parse(value)
        } catch (e: Exception) {
            throw AssertionError("$field must be an ISO-8601 string, got: $value", e)
        }
    }

    private fun principal() = UserSecurity(
        id = sender.id,
        email = sender.email,
        userPassword = "not-a-real-hash",
        userAuthorities = listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private fun mockUser(email: String) = User(
        firstName = "Test",
        lastName = "User",
        email = email,
        password = "not-a-real-hash",
    )
}
