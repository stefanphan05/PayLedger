package com.stefan.payment_api_service.models.exception

import java.util.UUID

class TransactionNotFoundException(id: UUID) : RuntimeException("Transaction with id $id was not found") {

}