package com.stefan.payment_api_service.exception.transaction

import com.stefan.payment_api_service.exception.ClientError
import java.util.UUID

class RecipientNotFoundException(message: String) : RuntimeException(message), ClientError {
    constructor(id: UUID) : this("Recipient $id not found")

    companion object {
        fun fromMessage(message: String) = RecipientNotFoundException(message)
    }
}