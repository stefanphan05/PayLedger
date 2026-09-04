package com.stefan.payment_api_service.transaction.model

import com.fasterxml.jackson.annotation.JsonFormat
import com.stefan.payment_api_service.idempotency.model.IdempotentRequest
import com.stefan.payment_api_service.transaction.model.Transaction
import com.stefan.payment_api_service.transaction.model.TransactionStatus
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TransactionRequestDTO(
    @field:NotNull(message = "Amount is required")
    @field:DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @field:Digits(integer = 15, fraction = 4, message = "Amount has too many digits")
    val amount: BigDecimal,

    @field:NotBlank(message = "Currency code is required")
    @field:Pattern(regexp = "^[A-Z]{3}$", message = "Currency code must be a 3-letter ISO 4217 code (e.g. USD)")
    val currencyCode: String,

    @field:NotNull(message = "RecipientID is required")
    val recipientId: UUID
) : IdempotentRequest {
    override fun canonicalForm(): String =
        listOf(
            amount.stripTrailingZeros().toPlainString(),
            currencyCode,
            recipientId.toString(),
        ).joinToString("|")
}

data class UpdateTransactionStatusDTO(
    @field:NotNull("Status is required")
    val status: TransactionStatus,
)

data class TransactionResponseDTO(
    val id: UUID,

    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,

    val currency: String,
    val status: TransactionStatus,
    val senderId: UUID,
    val recipientId: UUID,
    val createdAt: Instant?
) {
    companion object {
        fun from(transaction: Transaction): TransactionResponseDTO {
            return TransactionResponseDTO(
                id = transaction.id,
                amount = transaction.amount,
                currency = transaction.currency,
                status = transaction.transactionStatus,
                senderId = transaction.senderId,
                recipientId = transaction.recipientId,
                createdAt = transaction.createdAt
            )
        }
    }
}
