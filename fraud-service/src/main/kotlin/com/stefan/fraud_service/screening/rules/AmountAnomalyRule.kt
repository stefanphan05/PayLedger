package com.stefan.fraud_service.screening.rules

import com.stefan.fraud_service.config.FraudProperties
import com.stefan.fraud_service.screening.model.ScreenCandidate
import com.stefan.fraud_service.screening.model.SenderHistory
import com.stefan.fraud_service.screening.model.TriggeredRule
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A payment far outside what this sender normally sends.
 *
 * The only rule that is relative rather than absolute, so it is the only one that can
 * catch a takeover of an account whose owner never sends large amounts, while leaving a
 * genuinely high-volume sender alone.
 */
@Component
class AmountAnomalyRule(
    private val properties: FraudProperties
): FraudRule {
    override fun evaluate(candidate: ScreenCandidate, history: SenderHistory): TriggeredRule? {
        val rule = properties.rules.amountAnomaly

        // A mean over two payments is not a baseline. Without this the rule would fire
        // on every new user's third payment.
        if (history.baselineCount < rule.minimumHistory) {
            return null
        }

        if (history.baselineMean <= BigDecimal.ZERO) {
            return null
        }

        // The comparison the rule is actually named after. Everything above only
        // decides whether there is enough history to trust the mean.
        val threshold = history.baselineMean.multiply(rule.multiplier)
        if (candidate.amount <= threshold) {
            return null
        }

        val ratio = candidate.amount.divide(
            history.baselineMean,
            1,
            RoundingMode.HALF_UP
        )

        return TriggeredRule(
            rule = "AMOUNT_ANOMALY",
            weight = rule.weight,
            detail = "${ratio}x the ${rule.window.toDays()}d average",
        )
    }
}
