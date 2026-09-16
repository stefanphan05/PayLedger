package com.stefan.fraud_service.screening.rules

import com.stefan.fraud_service.config.FraudProperties
import com.stefan.fraud_service.screening.model.ScreenCandidate
import com.stefan.fraud_service.screening.model.SenderHistory
import com.stefan.fraud_service.screening.model.TriggeredRule
import org.springframework.stereotype.Component

/**
 * Too many payments from one sender, too fast
 */
@Component
class VelocityRule(
    private val properties: FraudProperties
): FraudRule {
    override fun evaluate(candidate: ScreenCandidate, history: SenderHistory): TriggeredRule? {
        val rule = properties.rules.velocity

        // The candidate counts itself, history holds only rows already written
        val attempts = history.recentCount + 1
        if (attempts <= rule.maxPayments) {
            return null
        }

        return TriggeredRule(
            rule = "VELOCITY",
            weight = rule.weight,
            detail = "$attempts in ${rule.window.seconds}s",
        )
    }
}