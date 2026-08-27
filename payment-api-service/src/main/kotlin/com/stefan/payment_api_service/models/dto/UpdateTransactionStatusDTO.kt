package com.stefan.payment_api_service.models.dto

import com.stefan.payment_api_service.models.enum.TransactionStatus
import org.jetbrains.annotations.NotNull

data class UpdateTransactionStatusDTO(
    @field:NotNull("Status is required")
    val status: TransactionStatus,
)
