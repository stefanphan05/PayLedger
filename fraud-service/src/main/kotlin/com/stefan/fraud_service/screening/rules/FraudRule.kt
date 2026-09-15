package com.stefan.fraud_service.screening.rules

import com.stefan.fraud_service.screening.model.ScreenCandidate
import com.stefan.fraud_service.screening.model.SenderHistory
import com.stefan.fraud_service.screening.model.TriggeredRule

interface FraudRule {
    fun evaluate(
        candidate: ScreenCandidate,
        history: SenderHistory
    ): TriggeredRule?
}