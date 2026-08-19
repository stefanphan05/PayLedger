package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.models.dto.TransactionRequestDTO
import com.stefan.payment_api_service.models.entity.Transaction
import com.stefan.payment_api_service.models.enum.TransactionStatus
import com.stefan.payment_api_service.models.exception.TransactionNotFoundException
import com.stefan.payment_api_service.repository.TransactionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class TransactionServiceTests {
    @Mock
    lateinit var transactionRepository: TransactionRepository

    @InjectMocks
    lateinit var transactionService: TransactionService

    @Test
    fun `getTransactionById returns transaction`() {
        val transaction = mockTransaction(currency = "USD", transactionStatus = TransactionStatus.COMPLETED)

        whenever(transactionRepository.findById(transaction.id)).thenReturn(Optional.of(transaction))

        val result = transactionService.getTransactionById(transaction.id)

        assertEquals(transaction.id, result.id)
        assertEquals("USD", result.currency)
        verify(transactionRepository).findById(transaction.id)
    }

    @Test
    fun `getTransactionById throws an exception if the transaction does not exist`() {
        val randomID = UUID.randomUUID()
        whenever(transactionRepository.findById(randomID)).thenReturn(Optional.empty())

        val exception = assertThrows<TransactionNotFoundException> { transactionService.getTransactionById(randomID) }
        assertTrue(exception.message?.contains(randomID.toString()) == true)
    }

    @Test
    fun `createTransaction creates a new transaction`() {
        val transactionRequestDTO: TransactionRequestDTO = TransactionRequestDTO(
            amount = BigDecimal.ONE,
            currencyCode = "USD"
        )

        whenever(transactionRepository.save(any())).thenAnswer { it.arguments[0] }

        val result = transactionService.createTransaction(
            transactionRequestDTO
        )

        assertEquals(TransactionStatus.PENDING, result.transactionStatus)
        assertEquals(transactionRequestDTO.amount, result.amount)
        assertEquals(transactionRequestDTO.currencyCode, result.currency)
        assertNotNull(result.id)
        verify(transactionRepository, times(1)).save(any())
    }

    private fun mockTransaction(
        id: UUID = UUID.randomUUID(),
        amount: BigDecimal = BigDecimal("15.90"),
        currency: String = "AUD",
        transactionStatus: TransactionStatus = TransactionStatus.PENDING
    ): Transaction {
        return Transaction(
            id = id,
            amount = amount,
            currency = currency,
            transactionStatus = transactionStatus,
        )
    }
}