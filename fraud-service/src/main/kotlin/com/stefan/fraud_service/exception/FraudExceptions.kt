package com.stefan.fraud_service.exception

import java.util.UUID

class DecisionNotFoundException(transactionId: UUID) :
    RuntimeException("No screening decision for transaction $transactionId")

class ReviewConflictException(transactionId: UUID, state: String) :
    RuntimeException("Transaction $transactionId is not awaiting review ($state)")