package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.exception.TransactionNotFoundException
import com.stefan.payment_api_service.models.entity.Transaction
import com.stefan.payment_api_service.models.entity.User
import com.stefan.payment_api_service.models.enum.TransactionStatus
import com.stefan.payment_api_service.repository.TransactionRepository
import com.stefan.payment_api_service.repository.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class PostgresIntegrationTests @Autowired constructor (
    val transactionService: TransactionService,
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

    // sender_id and recipient_id are NOT NULL with foreign keys to users,
    // so every transaction needs two real accounts behind it
    private lateinit var sender: User
    private lateinit var recipient: User

    @BeforeEach
    fun seedUsers() {
        sender = userRepository.save(mockUser("sender@example.com"))
        recipient = userRepository.save(mockUser("recipient@example.com"))
    }

    @AfterEach
    fun cleanUp() {
        // transactions first: the foreign keys are ON DELETE RESTRICT
        transactionRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `a saved transaction round-trips its parties through the database`() {
        val savedTransaction = saveMockTransaction()

        val newTransaction = transactionRepository.findById(savedTransaction.id).orElseThrow()

        assertEquals(sender.id, newTransaction.senderId)
        assertEquals(recipient.id, newTransaction.recipientId)
    }

    @Test
    fun `findByIDAndParty returns the transaction for either party`() {
        val savedTransaction = saveMockTransaction()

        assertTrue(transactionRepository.findByIDAndParty(savedTransaction.id, sender.id).isPresent)
        assertTrue(transactionRepository.findByIDAndParty(savedTransaction.id, recipient.id).isPresent)
    }

    @Test
    fun `findByIDAndParty returns nothing for a user who is not a party`() {
        val savedTransaction = saveMockTransaction()
        val stranger = userRepository.save(mockUser("stranger@example.com"))

        assertTrue(transactionRepository.findByIDAndParty(savedTransaction.id, stranger.id).isEmpty)
    }

    @Test
    fun `findAllByParty returns both sent and received transactions`() {
        val sent = saveMockTransaction()
        val received = saveMockTransaction(senderId = recipient.id, recipientId = sender.id)

        val page = transactionRepository.findAllByParty(sender.id, PageRequest.of(0, 20))

        assertEquals(2, page.totalElements)
        assertEquals(setOf(sent.id, received.id), page.content.map { it.id }.toSet())
    }

    @Test
    fun `findAllByParty does not return transactions the user has no part in`() {
        saveMockTransaction()
        val stranger = userRepository.save(mockUser("stranger@example.com"))

        val page = transactionRepository.findAllByParty(stranger.id, PageRequest.of(0, 20))

        assertEquals(0, page.totalElements)
    }

    @Test
    fun `the database rejects a transaction whose sender and recipient are the same user`() {
        assertThrows<DataIntegrityViolationException> {
            transactionRepository.saveAndFlush(
                Transaction(
                    amount = BigDecimal("15.90"),
                    currency = "AUD",
                    transactionStatus = TransactionStatus.PENDING,
                    senderId = sender.id,
                    recipientId = sender.id,
                )
            )
        }
    }

    @Test
    fun `the database rejects a transaction whose recipient does not exist`() {
        assertThrows<DataIntegrityViolationException> {
            transactionRepository.saveAndFlush(
                Transaction(
                    amount = BigDecimal("15.90"),
                    currency = "AUD",
                    transactionStatus = TransactionStatus.PENDING,
                    senderId = sender.id,
                    recipientId = UUID.randomUUID(),
                )
            )
        }
    }

    @Test
    fun `updateTransactionStatus saves the new status to the database successfully`() {
        val savedTransaction = saveMockTransaction()

        transactionService.updateTransactionStatus(savedTransaction.id, TransactionStatus.COMPLETED)

        val newTransaction = transactionRepository.findById(savedTransaction.id).orElseThrow()
        assertEquals(TransactionStatus.COMPLETED, newTransaction.transactionStatus)
    }

    @Test
    fun `updateTransactionStatus increments the version column`() {
        val savedTransaction = saveMockTransaction()
        val versionBeforeEdit = savedTransaction.version

        transactionService.updateTransactionStatus(savedTransaction.id, TransactionStatus.COMPLETED)

        val newTransaction = transactionRepository.findById(savedTransaction.id).orElseThrow()
        assertEquals(versionBeforeEdit + 1, newTransaction.version)
    }

    @Test
    fun `updateTransactionStatus throws TransactionNotFoundException and writes nothing when the transaction does not exist`() {
        val unknownId = UUID.randomUUID()

        assertThrows<TransactionNotFoundException> {
            transactionService.updateTransactionStatus(unknownId, TransactionStatus.FAILED)
        }

        assertTrue(transactionRepository.findById(unknownId).isEmpty)
        assertEquals(0, transactionRepository.count())
    }

    @Test
    fun `updateTransactionStatus throws ObjectOptimisticLockingFailureException when there're 2 writes`() {
        val savedTransaction = saveMockTransaction()

        // get the old transaction that loaded from the row before updating it
        val oldTransaction = transactionRepository.findById(savedTransaction.id).orElseThrow()

        // First write succeeds and bumps the version 0 ->  1
        transactionService.updateTransactionStatus(savedTransaction.id, TransactionStatus.FAILED)

        // OldTransaction still thinks the version is 0, so writing now should conflict
        oldTransaction.transactionStatus = TransactionStatus.COMPLETED
        assertThrows<ObjectOptimisticLockingFailureException> {
            transactionRepository.saveAndFlush(oldTransaction)
        }

        val newTransaction = transactionRepository.findById(savedTransaction.id).orElseThrow()
        assertEquals(TransactionStatus.FAILED, newTransaction.transactionStatus)
    }

    @ParameterizedTest
    @EnumSource(TransactionStatus::class)
    fun `updateTransactionStatus return the right TransactionStatus for each status value`(status: TransactionStatus) {
        val savedTransaction = saveMockTransaction()

        transactionService.updateTransactionStatus(savedTransaction.id, status)

        val newTransaction = transactionRepository.findById(savedTransaction.id).orElseThrow()
        assertEquals(status, newTransaction.transactionStatus)
    }

    private fun saveMockTransaction(
        amount: BigDecimal = BigDecimal("15.90"),
        currency: String = "AUD",
        transactionStatus: TransactionStatus = TransactionStatus.PENDING,
        senderId: UUID = sender.id,
        recipientId: UUID = recipient.id,
    ): Transaction {
        return transactionRepository.save(
            Transaction(
                amount = amount,
                currency = currency,
                transactionStatus = transactionStatus,
                senderId = senderId,
                recipientId = recipientId,
            )
        )
    }

    private fun mockUser(email: String): User {
        return User(
            firstName = "Test",
            lastName = "User",
            email = email,
            password = "not-a-real-hash",
        )
    }
}
