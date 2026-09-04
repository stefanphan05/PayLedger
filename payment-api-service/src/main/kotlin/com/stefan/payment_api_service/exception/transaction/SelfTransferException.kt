package com.stefan.payment_api_service.exception.transaction

import com.stefan.payment_api_service.exception.ClientError

class SelfTransferException : RuntimeException("Cannot send a transaction to yourself"), ClientError {
}