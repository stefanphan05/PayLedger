package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.models.dto.TransactionRequestDTO
import com.stefan.payment_api_service.models.entity.Transaction
import com.stefan.payment_api_service.models.enum.PaymentEventType
import com.stefan.payment_api_service.models.enum.TransactionStatus
import com.stefan.payment_api_service.exception.RecipientNotFoundException
import com.stefan.payment_api_service.exception.SelfTransferException
import com.stefan.payment_api_service.exception.TransactionNotFoundException
import com.stefan.payment_api_service.repository.TransactionRepository
import com.stefan.payment_api_service.repository.UserRepository
import com.stefan.payment_api_service.security.UserSecurity
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class TransactionServiceTests {
    @Mock
    lateinit var transactionRepository: TransactionRepository

    @Mock
    lateinit var userRepository: UserRepository

    @Mock
    lateinit var paymentEventPublisher: PaymentEventPublisher

    @InjectMocks
    lateinit var transactionService: TransactionService

    private val senderId: UUID = UUID.randomUUID()
    private val recipientId: UUID = UUID.randomUUID()

    @Test
    fun `getTransactionForRequester returns the transaction for the sender`() {
        val transaction = mockTransaction(currency = "USD", transactionStatus = TransactionStatus.COMPLETED)

        whenever(transactionRepository.findByIDAndParty(transaction.id, senderId))
            .thenReturn(Optional.of(transaction))

        val result = transactionService.getTransactionForRequester(transaction.id, userSecurity(senderId))

        assertEquals(transaction.id, result.id)
        assertEquals("USD", result.currency)
        verify(transactionRepository).findByIDAndParty(transaction.id, senderId)
    }

    @Test
    fun `getTransactionForRequester returns the transaction for the recipient`() {
        val transaction = mockTransaction()

        whenever(transactionRepository.findByIDAndParty(transaction.id, recipientId))
            .thenReturn(Optional.of(transaction))

        val result = transactionService.getTransactionForRequester(transaction.id, userSecurity(recipientId))

        assertEquals(transaction.id, result.id)
    }

    @Test
    fun `getTransactionForRequester throws an exception when the requester is not a party`() {
        val transaction = mockTransaction()
        val strangerId = UUID.randomUUID()

        whenever(transactionRepository.findByIDAndParty(transaction.id, strangerId))
            .thenReturn(Optional.empty())

        val exception = assertThrows<TransactionNotFoundException> {
            transactionService.getTransactionForRequester(transaction.id, userSecurity(strangerId))
        }

        assertTrue(exception.message?.contains(transaction.id.toString()) == true)
        // a stranger must never reach the unscoped lookup
        verify(transactionRepository, never()).findById(any())
    }

    @Test
    fun `getTransactionForRequester throws an exception if the transaction does not exist`() {
        val randomID = UUID.randomUUID()
        whenever(transactionRepository.findByIDAndParty(randomID, senderId)).thenReturn(Optional.empty())

        val exception = assertThrows<TransactionNotFoundException> {
            transactionService.getTransactionForRequester(randomID, userSecurity(senderId))
        }
        assertTrue(exception.message?.contains(randomID.toString()) == true)
    }

    @Test
    fun `getTransactionForRequester lets an admin read a transaction they are not a party to`() {
        val transaction = mockTransaction()
        val adminId = UUID.randomUUID()

        whenever(transactionRepository.findById(transaction.id)).thenReturn(Optional.of(transaction))

        val result = transactionService.getTransactionForRequester(
            transaction.id,
            userSecurity(adminId, "ROLE_USER", "ROLE_ADMIN")
        )

        assertEquals(transaction.id, result.id)
        verify(transactionRepository).findById(transaction.id)
        verify(transactionRepository, never()).findByIDAndParty(any(), any())
    }

    @Test
    fun `createTransaction creates a new transaction`() {
        val transactionRequestDTO = TransactionRequestDTO(
            amount = BigDecimal.ONE,
            currencyCode = "USD",
            recipientId = recipientId,
        )

        whenever(userRepository.existsById(recipientId)).thenReturn(true)
        whenever(transactionRepository.saveAndFlush(any())).thenAnswer { it.arguments[0] }

        val result = transactionService.createTransaction(transactionRequestDTO, senderId)

        assertEquals(TransactionStatus.PENDING, result.transactionStatus)
        assertEquals(transactionRequestDTO.amount, result.amount)
        assertEquals(transactionRequestDTO.currencyCode, result.currency)
        assertNotNull(result.id)
        verify(transactionRepository).saveAndFlush(any())
    }

    @Test
    fun `createTransaction stamps the sender from the caller and the recipient from the request`() {
        val transactionRequestDTO = TransactionRequestDTO(
            amount = BigDecimal("42.50"),
            currencyCode = "AUD",
            recipientId = recipientId,
        )

        whenever(userRepository.existsById(recipientId)).thenReturn(true)
        whenever(transactionRepository.saveAndFlush(any())).thenAnswer { it.arguments[0] }

        transactionService.createTransaction(transactionRequestDTO, senderId)

        val captor = argumentCaptor<Transaction>()
        verify(transactionRepository).saveAndFlush(captor.capture())
        assertEquals(senderId, captor.firstValue.senderId)
        assertEquals(recipientId, captor.firstValue.recipientId)
    }

    @Test
    fun `createTransaction throws SelfTransferException when the sender is the recipient`() {
        val transactionRequestDTO = TransactionRequestDTO(
            amount = BigDecimal.ONE,
            currencyCode = "USD",
            recipientId = senderId,
        )

        assertThrows<SelfTransferException> {
            transactionService.createTransaction(transactionRequestDTO, senderId)
        }

        // the self-check must short-circuit before any lookup or write
        verify(userRepository, never()).existsById(any())
        verify(transactionRepository, never()).saveAndFlush(any())
        // a rejected request never happened, so nothing may be announced downstream
        verify(paymentEventPublisher, never()).publish(any(), any())
    }

    @Test
    fun `createTransaction throws RecipientNotFoundException when the recipient does not exist`() {
        val transactionRequestDTO = TransactionRequestDTO(
            amount = BigDecimal.ONE,
            currencyCode = "USD",
            recipientId = recipientId,
        )

        whenever(userRepository.existsById(recipientId)).thenReturn(false)

        assertThrows<RecipientNotFoundException> {
            transactionService.createTransaction(transactionRequestDTO, senderId)
        }

        verify(transactionRepository, never()).saveAndFlush(any())
        verify(paymentEventPublisher, never()).publish(any(), any())
    }

    @Test
    fun `createTransaction publishes a PAYMENT_INITIATED event for the saved transaction`() {
        val transactionRequestDTO = TransactionRequestDTO(
            amount = BigDecimal("42.50"),
            currencyCode = "AUD",
            recipientId = recipientId,
        )

        whenever(userRepository.existsById(recipientId)).thenReturn(true)
        whenever(transactionRepository.saveAndFlush(any())).thenAnswer { it.arguments[0] }

        val saved = transactionService.createTransaction(transactionRequestDTO, senderId)

        val typeCaptor = argumentCaptor<PaymentEventType>()
        val transactionCaptor = argumentCaptor<Transaction>()
        verify(paymentEventPublisher).publish(typeCaptor.capture(), transactionCaptor.capture())

        assertEquals(PaymentEventType.PAYMENT_INITIATED, typeCaptor.firstValue)
        // the event must describe the row that was saved, not the incoming request
        assertEquals(saved.id, transactionCaptor.firstValue.id)
    }

    @Test
    fun `getTransactionForUser delegates to the repository with the given pageable`() {
        val pageable = PageRequest.of(1, 5)
        val page = PageImpl(listOf(mockTransaction()))

        whenever(transactionRepository.findAllByParty(senderId, pageable)).thenReturn(page)

        val result = transactionService.getTransactionForUser(senderId, pageable)

        assertEquals(1, result.content.size)
        verify(transactionRepository).findAllByParty(senderId, pageable)
    }

    @Test
    fun `updateTransactionStatus updates transaction status successfully`() {
        val transaction = mockTransaction()
        whenever(transactionRepository.findById(transaction.id)).thenReturn(Optional.of(transaction))
        whenever(transactionRepository.save(any())).thenAnswer { it.arguments[0] }

        val updatedResult = transactionService.updateTransactionStatus(transaction.id, TransactionStatus.FAILED)

        assertEquals(TransactionStatus.FAILED, updatedResult.transactionStatus)
        verify(transactionRepository).save(transaction)
        verify(transactionRepository).findById(transaction.id)
    }

    @Test
    fun `updateTransactionStatus publishes a PAYMENT_STATUS_CHANGED event carrying the new status`() {
        val transaction = mockTransaction(transactionStatus = TransactionStatus.PENDING)
        whenever(transactionRepository.findById(transaction.id)).thenReturn(Optional.of(transaction))
        whenever(transactionRepository.save(any())).thenAnswer { it.arguments[0] }

        transactionService.updateTransactionStatus(transaction.id, TransactionStatus.COMPLETED)

        val typeCaptor = argumentCaptor<PaymentEventType>()
        val transactionCaptor = argumentCaptor<Transaction>()
        verify(paymentEventPublisher).publish(typeCaptor.capture(), transactionCaptor.capture())

        assertEquals(PaymentEventType.PAYMENT_STATUS_CHANGED, typeCaptor.firstValue)
        // published after the new status was applied - a consumer must see COMPLETED,
        // not the PENDING the row held on the way in
        assertEquals(TransactionStatus.COMPLETED, transactionCaptor.firstValue.transactionStatus)
    }

    @Test
    fun `updateTransactionStatus throws an exception if the transaction does not exist`() {
        val id = UUID.randomUUID()
        whenever(transactionRepository.findById(id)).thenReturn(Optional.empty())

        assertThrows<TransactionNotFoundException> {
            transactionService.updateTransactionStatus(id, TransactionStatus.FAILED)
        }
        verify(transactionRepository).findById(id)
        verify(transactionRepository, never()).save(any())
        verify(paymentEventPublisher, never()).publish(any(), any())
    }

    private fun mockTransaction(
        id: UUID = UUID.randomUUID(),
        amount: BigDecimal = BigDecimal("15.90"),
        currency: String = "AUD",
        transactionStatus: TransactionStatus = TransactionStatus.PENDING,
        senderId: UUID = this.senderId,
        recipientId: UUID = this.recipientId,
    ): Transaction {
        return Transaction(
            id = id,
            amount = amount,
            currency = currency,
            transactionStatus = transactionStatus,
            senderId = senderId,
            recipientId = recipientId,
        )
    }

    private fun userSecurity(id: UUID, vararg roles: String = arrayOf("ROLE_USER")): UserSecurity {
        return UserSecurity(
            id = id,
            email = "user-$id@example.com",
            userPassword = "encoded",
            userAuthorities = roles.map { SimpleGrantedAuthority(it) },
        )
    }
}
