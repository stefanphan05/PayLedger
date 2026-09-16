package com.stefan.fraud_service.config

import com.stefan.fraud_service.screening.model.FraudAction
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration

/**
 * Everything threshold and weight, in one place, changeable
 *
 * This is the argument for rules over a model in a single class
 */
@ConfigurationProperties(prefix = "fraud")
data class FraudProperties(
    /**
     * score >= blockThreshold -> blocks
     * score >= reviewThreshold -> holds
     * below that -> goes through
     */
    val blockThreshold: Int = 70,
    val reviewThreshold: Int = 40,
    val rules: Rules = Rules(),
) {
    fun actionFor(score: Int): FraudAction = when {
        score >= blockThreshold -> FraudAction.BLOCK
        score >= reviewThreshold -> FraudAction.REVIEW
        else -> FraudAction.ALLOW
    }

    data class Rules(
        val velocity: Velocity = Velocity(),
        val amountCeiling: AmountCeiling = AmountCeiling(),
        val amountAnomaly: AmountAnomaly = AmountAnomaly(),
        val newRecipientLarge: NewRecipientLarge = NewRecipientLarge(),
        val fanOut: FanOut = FanOut(),
    )

    data class Velocity(
        val window: Duration = Duration.ofSeconds(60),
        val maxPayments: Long = 5,
        val weight: Int = 40,
    )

    data class AmountCeiling(
        val limit: BigDecimal = BigDecimal("10000"),
        val weight: Int = 40,
    )

    data class AmountAnomaly(
        val window: Duration = Duration.ofDays(30),
        val multiplier: BigDecimal = BigDecimal("10"),
        val minimumHistory: Long = 5,
        val weight: Int = 25,
    )

    data class NewRecipientLarge(
        val minimumAmount: BigDecimal = BigDecimal("1000"),
        val weight: Int = 30,
    )

    data class FanOut(
        val window: Duration = Duration.ofMinutes(10),
        val maxRecipients: Long = 4,
        val weight: Int = 35,
    )

    data class Insights(
        val baseUrl: String = "http://localhost:8000",
        val pollInterval: Duration = Duration.ofSeconds(10),
        val batchSize: Int = 5,
        val maxAttempts: Int = 3,
        val timeout: Duration = Duration.ofSeconds(30),
    )
}