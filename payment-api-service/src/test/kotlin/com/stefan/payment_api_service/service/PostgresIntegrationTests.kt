package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.exception.TransactionNotFoundException
import com.stefan.payment_api_service.models.entity.Transaction
import com.stefan.payment_api_service.models.enum.TransactionStatus
import com.stefan.payment_api_service.repository.TransactionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest
@Testcontainers
class PostgresIntegrationTests @Autowired constructor (
    val transactionService: TransactionService,
    val transactionRepository: TransactionRepository
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
    }

    @AfterEach
    fun cleanUp() {
        transactionRepository.deleteAll()
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
    ): Transaction {
        return transactionRepository.save(
            Transaction(
                amount = amount,
                currency = currency,
                transactionStatus = transactionStatus,
            )
        )
    }
}