package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.exception.RecipientNotFoundException
import com.stefan.payment_api_service.exception.SelfTransferException
import com.stefan.payment_api_service.models.dto.TransactionRequestDTO
import com.stefan.payment_api_service.models.entity.Transaction
import com.stefan.payment_api_service.models.enum.TransactionStatus
import com.stefan.payment_api_service.exception.TransactionNotFoundException
import com.stefan.payment_api_service.repository.TransactionRepository
import com.stefan.payment_api_service.repository.UserRepository
import com.stefan.payment_api_service.security.UserSecurity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TransactionService(
    private val repository: TransactionRepository,
    private val userRepository: UserRepository,
) {
    private fun getTransactionById(id: UUID): Transaction {
        return repository.findById(id)
            .orElseThrow { TransactionNotFoundException(id) }
    }

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

        return repository.save(transaction)
    }

    fun getTransactionForRequester(id : UUID, requester: UserSecurity): Transaction {
        if (requester.isAdmin()) return getTransactionById(id)

        return repository.findByIDAndParty(id, requester.id)
            .orElseThrow { TransactionNotFoundException(id) }
    }

    @Transactional
    fun updateTransactionStatus(id: UUID, transactionStatus: TransactionStatus): Transaction {
        val transaction = getTransactionById(id)
        transaction.transactionStatus = transactionStatus
        return repository.save(transaction)
    }
}