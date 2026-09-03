package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.exception.RecipientNotFoundException
import com.stefan.payment_api_service.exception.SelfTransferException
import com.stefan.payment_api_service.models.dto.TransactionRequestDTO
import com.stefan.payment_api_service.models.entity.Transaction
import com.stefan.payment_api_service.models.enum.TransactionStatus
import com.stefan.payment_api_service.exception.TransactionNotFoundException
import com.stefan.payment_api_service.models.enum.PaymentEventType
import com.stefan.payment_api_service.repository.TransactionRepository
import com.stefan.payment_api_service.repository.UserRepository
import com.stefan.payment_api_service.security.UserSecurity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TransactionService(
    private val repository: TransactionRepository,
    private val userRepository: UserRepository,
    private val paymentEventPublisher: PaymentEventPublisher,
) {
    private fun getTransactionById(id: UUID): Transaction {
        return repository.findById(id)
            .orElseThrow { TransactionNotFoundException(id) }
    }

    /**
     * @Transactional is what makes the outbox an outbox: the transaction row and the
     * event row commit as one unit, or neither does. Without it these are two separate
     * autocommits and a crash between them loses the event - the failure ADR-0004
     * exists to prevent.
     *
     * saveAndFlush, not save, for the same reason: inside a transaction save() only
     * queues the insert, so @CreationTimestamp has not fired yet and the event payload
     * would carry "createdAt": null.
     */
    @Transactional
    fun createTransaction(transactionRequestDTO: TransactionRequestDTO, senderId: UUID): Transaction {
        if (senderId == transactionRequestDTO.recipientId) throw SelfTransferException()
        if (!userRepository.existsById(transactionRequestDTO.recipientId)) throw RecipientNotFoundException(transactionRequestDTO.recipientId)

        val transaction: Transaction = Transaction(
            amount = transactionRequestDTO.amount,
            currency = transactionRequestDTO.currencyCode,
            transactionStatus = TransactionStatus.PENDING,
            senderId = senderId,
            recipientId = transactionRequestDTO.recipientId,
        )

        val savedTransaction = repository.saveAndFlush(transaction)
        paymentEventPublisher.publish(PaymentEventType.PAYMENT_INITIATED, savedTransaction)
        return savedTransaction
    }

    fun getTransactionForRequester(id : UUID, requester: UserSecurity): Transaction {
        if (requester.isAdmin()) return getTransactionById(id)

        return repository.findByIDAndParty(id, requester.id)
            .orElseThrow { TransactionNotFoundException(id) }
    }

    fun getTransactionForUser(userId: UUID, pageable: Pageable): Page<Transaction> {
        return repository.findAllByParty(
            userId = userId,
            pageable = pageable
        )
    }

    @Transactional
    fun updateTransactionStatus(id: UUID, transactionStatus: TransactionStatus): Transaction {
        val transaction = getTransactionById(id)
        transaction.transactionStatus = transactionStatus

        val savedTransaction = repository.save(transaction)
        paymentEventPublisher.publish(PaymentEventType.PAYMENT_STATUS_CHANGED, savedTransaction)
        return savedTransaction
    }
}