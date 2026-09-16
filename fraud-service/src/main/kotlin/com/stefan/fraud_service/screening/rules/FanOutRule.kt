package com.stefan.fraud_service.screening.rules

import com.stefan.fraud_service.config.FraudProperties
import com.stefan.fraud_service.screening.model.ScreenCandidate
import com.stefan.fraud_service.screening.model.SenderHistory
import com.stefan.fraud_service.screening.model.TriggeredRule
import org.springframework.stereotype.Component

/**
 * One sender paying many different people in a short window.
 */
@Component
class FanOutRule(private val properties: FraudProperties) : FraudRule {

    override fun evaluate(candidate: ScreenCandidate, history: SenderHistory): TriggeredRule? {
        val rule = properties.rules.fanOut
        if (history.distinctRecipients <= rule.maxRecipients) {
            return null
        }

        return TriggeredRule(
            rule = "FAN_OUT",
            weight = rule.weight,
            detail = "${history.distinctRecipients} recipients in ${rule.window.toMinutes()}m"
        )
    }
}