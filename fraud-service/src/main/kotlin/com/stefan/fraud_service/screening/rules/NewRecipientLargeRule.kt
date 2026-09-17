package com.stefan.fraud_service.screening.rules

import com.stefan.fraud_service.config.FraudProperties
import com.stefan.fraud_service.screening.model.ScreenCandidate
import com.stefan.fraud_service.screening.model.SenderHistory
import com.stefan.fraud_service.screening.model.TriggeredRule
import org.springframework.stereotype.Component

/**
 * A large first payment to someone this sender has never paid
 */
@Component
class NewRecipientLargeRule (
    private val properties: FraudProperties
): FraudRule {
    override fun evaluate(candidate: ScreenCandidate, history: SenderHistory): TriggeredRule? {
        val rule = properties.rules.newRecipientLarge

        // A withdrawal has no recipient, so "never paid them before" means nothing.
        if (candidate.recipientId == null) {
            return null
        }

        if (history.priorToRecipient > 0) {
            return null
        }

        if (candidate.amount <= rule.minimumAmount) {
            return null
        }

        return TriggeredRule(
            rule = "NEW_RECIPIENT_LARGE",
            weight = rule.weight,
            detail = "first to this recipient, ${candidate.amount} ${candidate.currency}",
        )
    }
}