package com.stefan.payment_api_service.exception

class SelfTransferException : RuntimeException("Cannot send a transaction to yourself"), ClientError {
}