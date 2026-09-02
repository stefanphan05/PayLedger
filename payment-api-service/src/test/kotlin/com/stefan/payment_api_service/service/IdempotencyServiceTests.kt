package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.exception.IdempotencyConflictException
import com.stefan.payment_api_service.exception.IdempotencyKeyReuseException
import com.stefan.payment_api_service.exception.SelfTransferException
import com.stefan.payment_api_service.models.dto.IdempotencyRecord
import com.stefan.payment_api_service.models.enum.IdempotencyState
import com.stefan.payment_api_service.repository.IdempotencyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdempotencyServiceTests {

    @Mock lateinit var repository: IdempotencyRepository

    private val replayer = ClientErrorReplayer()
    private val jsonMapper: JsonMapper = JsonMapper.builder().build()
    private lateinit var service: IdempotencyService

    private val userId: UUID = UUID.randomUUID()
    private val key = "key-00000001"
    private val hash = "hash"

    @BeforeEach
    fun setUp() {
        service = IdempotencyService(repository, replayer, jsonMapper)
    }

    private fun execute(block: () -> ResponseEntity<String>) =
        service.execute(userId, key, hash, String::class.java, block)

    private fun created() = ResponseEntity.status(HttpStatus.CREATED).body("ok")

    @Test
    fun `winning the reservation runs the block and stores the response`() {
        whenever(repository.reserve(userId, key, hash)).thenReturn(true)

        val response = execute { created() }

        assertEquals(HttpStatus.CREATED, response.statusCode)
        verify(repository).markInProgress(userId, key, hash)
        verify(repository).complete(any(), any(), any())
    }

    @Test
    fun `a deterministic rejection is stored so it can be replayed`() {
        whenever(repository.reserve(userId, key, hash)).thenReturn(true)

        assertThrows<SelfTransferException> { execute { throw SelfTransferException() } }

        verify(repository).fail(any(), any(), any())
        verify(repository, never()).holdBriefly(any(), any())
        verify(repository, never()).release(any(), any())
    }

    /**
     * The regression guard for the duplicate-payment hole. createTransaction is not
     * transactional, so a connection drop at commit can throw *after* the row landed.
     * Deleting the key here would let the retry create a second payment, so this must
     * hold the key instead. Collapsing these two branches into one release() is the
     * "simplification" that reintroduces the bug.
     */
    @Test
    fun `an ambiguous database failure holds the key instead of releasing it`() {
        whenever(repository.reserve(userId, key, hash)).thenReturn(true)

        assertThrows<DataAccessResourceFailureException> {
            execute { throw DataAccessResourceFailureException("connection reset at commit") }
        }

        verify(repository).holdBriefly(userId, key)
        verify(repository, never()).release(any(), any())
        verify(repository, never()).fail(any(), any(), any())
    }

    @Test
    fun `a failed markInProgress releases the key, since nothing was attempted yet`() {
        whenever(repository.reserve(userId, key, hash)).thenReturn(true)
        whenever(repository.markInProgress(userId, key, hash))
            .thenThrow(DataAccessResourceFailureException("redis down"))

        assertThrows<DataAccessResourceFailureException> { execute { created() } }

        verify(repository).release(userId, key)
    }

    @Test
    fun `a bookkeeping failure never replaces the caller's error`() {
        whenever(repository.reserve(userId, key, hash)).thenReturn(true)
        whenever(repository.fail(any(), any(), any()))
            .thenThrow(DataAccessResourceFailureException("redis down"))

        // Without the inner guard this surfaces as a 500 instead of the 400 the
        // client earned.
        assertThrows<SelfTransferException> { execute { throw SelfTransferException() } }
    }

    @Test
    fun `a committed transaction is still returned when its record cannot be stored`() {
        whenever(repository.reserve(userId, key, hash)).thenReturn(true)
        whenever(repository.complete(any(), any(), any()))
            .thenThrow(DataAccessResourceFailureException("redis down"))

        // The payment happened. A 500 here would tell the client otherwise and send
        // them into a retry.
        assertEquals(HttpStatus.CREATED, execute { created() }.statusCode)
    }

    @Test
    fun `a mismatched fingerprint is rejected before the state is considered`() {
        whenever(repository.reserve(userId, key, hash)).thenReturn(false)
        whenever(repository.find(userId, key))
            .thenReturn(IdempotencyRecord(IdempotencyState.COMPLETED, "a-different-hash"))

        assertThrows<IdempotencyKeyReuseException> { execute { created() } }
    }

    @Test
    fun `a key still in flight is refused rather than run twice`() {
        whenever(repository.reserve(userId, key, hash)).thenReturn(false)
        whenever(repository.find(userId, key))
            .thenReturn(IdempotencyRecord(IdempotencyState.IN_PROGRESS, hash))

        assertThrows<IdempotencyConflictException> { execute { created() } }
    }

    @Test
    fun `a stored failure with no message replays without a null pointer`() {
        whenever(repository.reserve(userId, key, hash)).thenReturn(false)
        whenever(repository.find(userId, key)).thenReturn(
            IdempotencyRecord(
                state = IdempotencyState.FAILED,
                requestHash = hash,
                errorType = "SelfTransferException",
                errorMessage = null,
            )
        )

        // A ClientError with a null message used to return a clean 4xx first time
        // and then NPE on every retry for the rest of the TTL.
        assertThrows<SelfTransferException> { execute { created() } }
    }
}
