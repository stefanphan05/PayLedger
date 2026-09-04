package com.stefan.payment_api_service.idempotency

import com.stefan.payment_api_service.auth.model.User
import com.stefan.payment_api_service.transaction.repository.TransactionRepository
import com.stefan.payment_api_service.auth.repository.UserRepository
import com.stefan.payment_api_service.shared.security.JwtUtility
import com.stefan.payment_api_service.idempotency.service.IdempotencyService.Companion.HEADER_IDEMPOTENCY_KEY
import com.stefan.payment_api_service.idempotency.service.IdempotencyService.Companion.HEADER_IDEMPOTENT_REPLAY
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Proves the guarantee that ordinary sequential tests cannot: when N clients
 * retry the *same* Idempotency-Key at the same instant, exactly one payment is
 * created.
 *
 * A real server on a real port is required — MockMvc executes in the caller's
 * thread, so it can never produce the contention this is trying to measure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ConcurrentRetryTests @Autowired constructor(
    val transactionRepository: TransactionRepository,
    val userRepository: UserRepository,
    val jwtUtility: JwtUtility,
    val redis: StringRedisTemplate,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))

        @Container
        @ServiceConnection("redis")
        @JvmStatic
        val redisContainer: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    @Value("\${local.server.port}")
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    private lateinit var sender: User
    private lateinit var recipient: User
    private lateinit var token: String

    @BeforeEach
    fun seedUsers() {
        sender = userRepository.save(mockUser("sender@example.com"))
        recipient = userRepository.save(mockUser("recipient@example.com"))
        token = jwtUtility.generateToken(sender.id, sender.email)
    }

    @AfterEach
    fun cleanUp() {
        transactionRepository.deleteAll()
        userRepository.deleteAll()
        redis.connectionFactory?.getConnection()?.serverCommands()?.flushAll()
    }

    @ParameterizedTest(name = "{0} simultaneous retries create exactly one transaction")
    @ValueSource(ints = [10, 50, 200])
    fun `simultaneous retries of one key create exactly one transaction`(clients: Int) {
        val key = "race-${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val barrier = CyclicBarrier(clients)
        val pool = Executors.newFixedThreadPool(clients)

        val outcomes = try {
            (1..clients)
                .map {
                    pool.submit<Outcome> {
                        // Every thread parks here until the last one arrives, so the
                        // requests leave together instead of trickling out.
                        barrier.await(30, TimeUnit.SECONDS)
                        send(key)
                    }
                }
                .map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // The guarantee. Everything else is diagnosis.
        assertEquals(
            1, transactionRepository.count(),
            "$clients concurrent retries must leave exactly one transaction"
        )

        // Exactly one client did the creating; a 201 carrying the replay header
        // is a retry being served the original response, not a second write.
        assertEquals(
            1, outcomes.count { it.status == 201 && !it.replayed },
            "exactly one client should have created the transaction"
        )

        // The losers either replayed (winner had committed) or were told the
        // request was still in flight. Any other status means SETNX let something
        // through it should not have.
        assertTrue(
            outcomes.all { it.status == 201 || it.status == 409 },
            "unexpected statuses: ${outcomes.map { it.status }.distinct().sorted()}"
        )

        println(
            "clients=$clients  created=${outcomes.count { it.status == 201 && !it.replayed }}" +
                "  replayed=${outcomes.count { it.status == 201 && it.replayed }}" +
                "  in-flight-409=${outcomes.count { it.status == 409 }}" +
                "  rows=${transactionRepository.count()}"
        )
    }

    @ParameterizedTest(name = "{0} simultaneous requests with distinct keys create {0} transactions")
    @ValueSource(ints = [10, 50])
    fun `simultaneous requests with distinct keys are not suppressed`(clients: Int) {
        val barrier = CyclicBarrier(clients)
        val pool = Executors.newFixedThreadPool(clients)

        val outcomes = try {
            (1..clients)
                .map { n ->
                    pool.submit<Outcome> {
                        barrier.await(30, TimeUnit.SECONDS)
                        send("distinct-key-%05d".format(n))
                    }
                }
                .map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // The inverse guarantee: idempotency must not collapse genuinely
        // different requests just because they arrive together.
        assertEquals(clients.toLong(), transactionRepository.count())
        assertEquals(clients, outcomes.count { it.status == 201 && !it.replayed })
    }

    private fun send(idempotencyKey: String): Outcome {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/transactions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .header(HEADER_IDEMPOTENCY_KEY, idempotencyKey)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """{"amount":"15.90","currencyCode":"AUD","recipientId":"${recipient.id}"}"""
                )
            )
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        return Outcome(
            status = response.statusCode(),
            replayed = response.headers().firstValue(HEADER_IDEMPOTENT_REPLAY).isPresent,
        )
    }

    private data class Outcome(val status: Int, val replayed: Boolean)

    private fun mockUser(email: String) = User(
        firstName = "Test",
        lastName = "User",
        email = email,
        password = "not-a-real-hash",
    )
}
