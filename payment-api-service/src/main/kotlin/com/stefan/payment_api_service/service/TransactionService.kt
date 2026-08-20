package com.stefan.payment_api_service.service

import com.stefan.payment_api_service.models.dto.TransactionRequestDTO
import com.stefan.payment_api_service.models.entity.Transaction
import com.stefan.payment_api_service.models.enum.TransactionStatus
import com.stefan.payment_api_service.exception.TransactionNotFoundException
import com.stefan.payment_api_service.repository.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TransactionService(
    val repository: TransactionRepository,
) {
    fun getTransactionById(id: UUID): Transaction {
        return repository.findById(id)
            .orElseThrow { TransactionNotFoundException(id) }
    }

    fun createTransaction(transactionRequestDTO: TransactionRequestDTO): Transaction {
        val transaction: Transaction = Transaction(
            amount = transactionRequestDTO.amount,
            currency = transactionRequestDTO.currencyCode,
            transactionStatus = TransactionStatus.PENDING,
        )

        return repository.save(transaction)
    }

    @Transactional
    fun updateTransactionStatus(id: UUID, transactionStatus: TransactionStatus): Transaction {
        val transaction = getTransactionById(id)
        transaction.transactionStatus = transactionStatus
        return repository.save(transaction)
    }
}