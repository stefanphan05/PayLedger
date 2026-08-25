package com.stefan.payment_api_service.models.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.stefan.payment_api_service.models.entity.Transaction
import com.stefan.payment_api_service.models.enum.TransactionStatus
import java.math.BigDecimal
import java.util.UUID
import java.time.Instant

data class TransactionResponseDTO(
    val id: UUID,

    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,

    val currency: String,
    val status: TransactionStatus,
    val createdAt: Instant?
) {
    companion object {
        fun from(transaction: Transaction): TransactionResponseDTO {
            return TransactionResponseDTO(
                id = transaction.id,
                amount = transaction.amount,
                currency = transaction.currency,
                status = transaction.transactionStatus,
                createdAt = transaction.createdAt
            )
        }
    }
}
