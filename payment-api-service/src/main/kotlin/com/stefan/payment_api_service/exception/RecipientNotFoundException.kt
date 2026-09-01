package com.stefan.payment_api_service.exception

import java.util.UUID

class RecipientNotFoundException(id: UUID) : RuntimeException("Recipient with id: $id was not found") {
}