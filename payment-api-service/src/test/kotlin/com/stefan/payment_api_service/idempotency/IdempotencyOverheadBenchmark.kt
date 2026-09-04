package com.stefan.payment_api_service.idempotency

import com.stefan.payment_api_service.transaction.model.TransactionRequestDTO
import com.stefan.payment_api_service.transaction.model.TransactionResponseDTO
import com.stefan.payment_api_service.auth.model.User
import com.stefan.payment_api_service.transaction.repository.TransactionRepository
import com.stefan.payment_api_service.auth.repository.UserRepository
import com.stefan.payment_api_service.idempotency.service.RequestHasher
import com.stefan.payment_api_service.idempotency.service.IdempotencyService
import com.stefan.payment_api_service.transaction.service.TransactionService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.util.UUID

/**
 * Measures what the idempotency layer actually costs by running the same write
 * with and without it, interleaved, against real Postgres and Redis.
 *
 * Interleaving matters: running one path to completion and then the other would
 * let table growth, JIT and GC drift land entirely on whichever went second.
 * Alternating means both paths see the same conditions.
 *
 * The delta covers exactly what the layer adds to a create: SHA-256 of the
 * canonical body, the SETNX reservation, and two KEEPTTL state writes.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("benchmark")
class IdempotencyOverheadBenchmark @Autowired constructor(
    val transactionService: TransactionService,
    val idempotencyService: IdempotencyService,
    val requestHasher: RequestHasher,
    val transactionRepository: TransactionRepository,
    val userRepository: UserRepository,
) {
    companion object {
        private const val WARMUP = 500
        private const val ITERATIONS = 3000

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

    private lateinit var sender: User
    private lateinit var recipient: User

    @BeforeEach
    fun seedUsers() {
        transactionRepository.deleteAll()
        userRepository.deleteAll()
        sender = userRepository.save(mockUser("bench-sender@example.com"))
        recipient = userRepository.save(mockUser("bench-recipient@example.com"))
    }

    @Test
    fun `measure the latency the idempotency layer adds to a create`() {
        repeat(WARMUP) {
            timeBaseline()
            timeWithIdempotency()
        }

        val baseline = LongArray(ITERATIONS)
        val guarded = LongArray(ITERATIONS)

        for (i in 0 until ITERATIONS) {
            baseline[i] = timeBaseline()
            guarded[i] = timeWithIdempotency()
        }

        report("POST create - no idempotency", baseline)
        report("POST create - with idempotency", guarded)
        reportDelta(baseline, guarded)
    }

    @Test
    fun `measure the replay path, which serves a retry without touching Postgres`() {
        val key = "replay-bench-key"
        val request = newRequest()
        val hash = requestHasher.hash(request)

        // Prime the key so every measured call takes the replay branch.
        idempotencyService.execute(sender.id, key, hash, TransactionResponseDTO::class.java) {
            ResponseEntity.status(HttpStatus.CREATED)
                .body(TransactionResponseDTO.from(transactionService.createTransaction(request, sender.id)))
        }

        repeat(WARMUP) { replay(key, hash) }

        val samples = LongArray(ITERATIONS)
        for (i in 0 until ITERATIONS) {
            val start = System.nanoTime()
            replay(key, hash)
            samples[i] = System.nanoTime() - start
        }

        report("Replay a retry (2 Redis ops, 0 SQL)", samples)
    }

    private fun replay(key: String, hash: String) =
        idempotencyService.execute(sender.id, key, hash, TransactionResponseDTO::class.java) {
            error("the replay path must never reach the handler")
        }

    private fun timeBaseline(): Long {
        val request = newRequest()
        val start = System.nanoTime()
        // Mirrors exactly what the guarded path runs inside its block. Without the
        // DTO mapping here, the delta would charge the idempotency layer for work
        // the controller does either way.
        ResponseEntity.status(HttpStatus.CREATED)
            .body(TransactionResponseDTO.from(transactionService.createTransaction(request, sender.id)))
        return System.nanoTime() - start
    }

    private fun timeWithIdempotency(): Long {
        val request = newRequest()
        val key = UUID.randomUUID().toString().replace("-", "")
        val start = System.nanoTime()
        idempotencyService.execute(sender.id, key, requestHasher.hash(request), TransactionResponseDTO::class.java) {
            ResponseEntity.status(HttpStatus.CREATED)
                .body(TransactionResponseDTO.from(transactionService.createTransaction(request, sender.id)))
        }
        return System.nanoTime() - start
    }

    private fun newRequest() = TransactionRequestDTO(
        amount = BigDecimal("15.90"),
        currencyCode = "AUD",
        recipientId = recipient.id,
    )

    private fun report(label: String, samplesNanos: LongArray) {
        val sorted = samplesNanos.clone().also { it.sort() }
        println(
            "%-38s n=%d  p50=%.3fms  p95=%.3fms  p99=%.3fms  max=%.3fms  mean=%.3fms".format(
                label, sorted.size,
                ms(percentile(sorted, 50.0)), ms(percentile(sorted, 95.0)),
                ms(percentile(sorted, 99.0)), ms(sorted.last()),
                sorted.average() / 1_000_000.0,
            )
        )
    }

    private fun reportDelta(baseline: LongArray, guarded: LongArray) {
        val b = baseline.clone().also { it.sort() }
        val g = guarded.clone().also { it.sort() }
        println(
            "%-38s p50=+%.3fms  p95=+%.3fms  p99=+%.3fms  mean=+%.3fms".format(
                "OVERHEAD added by idempotency",
                ms(percentile(g, 50.0) - percentile(b, 50.0)),
                ms(percentile(g, 95.0) - percentile(b, 95.0)),
                ms(percentile(g, 99.0) - percentile(b, 99.0)),
                (g.average() - b.average()) / 1_000_000.0,
            )
        )
    }

    private fun percentile(sortedNanos: LongArray, p: Double): Long {
        val rank = Math.ceil(p / 100.0 * sortedNanos.size).toInt().coerceIn(1, sortedNanos.size)
        return sortedNanos[rank - 1]
    }

    private fun ms(nanos: Long) = nanos / 1_000_000.0

    private fun mockUser(email: String) = User(
        firstName = "Bench",
        lastName = "User",
        email = email,
        password = "not-a-real-hash",
    )
}
