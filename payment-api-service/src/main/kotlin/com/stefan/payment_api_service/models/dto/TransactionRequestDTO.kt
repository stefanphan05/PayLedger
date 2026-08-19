package com.stefan.payment_api_service.models.dto

import com.stefan.payment_api_service.models.enum.TransactionStatus
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal

data class TransactionRequestDTO(
    @field:NotNull(message = "Amount is required")
    @field:DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @field:Digits(integer = 15, fraction = 4, message = "Amount has too many digits")
    val amount: BigDecimal,

    @field:NotBlank(message = "Currency code is required")
    @field:Pattern(regexp = "^[A-Z]{3}$", message = "Currency code must be a 3-letter ISO 4217 code (e.g. USD)")
    val currencyCode: String,
)
