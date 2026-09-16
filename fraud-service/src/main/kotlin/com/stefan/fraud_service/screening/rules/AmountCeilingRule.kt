package com.stefan.fraud_service.screening.rules

import com.stefan.fraud_service.config.FraudProperties
import com.stefan.fraud_service.screening.model.ScreenCandidate
import com.stefan.fraud_service.screening.model.SenderHistory
import com.stefan.fraud_service.screening.model.TriggeredRule
import org.springframework.stereotype.Component

/**
 * A single payment above a flat ceiling.
 *
 * The only rule that needs no history, which is why it earns its place: it is the one
 * thing that can fire on a brand-new sender's very first payment, when every count in
 * SenderHistory is still zero.
 */
@Component
class AmountCeilingRule(
    private val properties: FraudProperties
): FraudRule {
    override fun evaluate(candidate: ScreenCandidate, history: SenderHistory): TriggeredRule? {
        val rule = properties.rules.amountCeiling

        if (candidate.amount <= rule.limit) {
            return null
        }

        return TriggeredRule(
            rule = "AMOUNT_CEILING",
            weight = rule.weight,
            detail = "${candidate.amount} ${candidate.currency} over ${rule.limit}",
        )
    }
}
