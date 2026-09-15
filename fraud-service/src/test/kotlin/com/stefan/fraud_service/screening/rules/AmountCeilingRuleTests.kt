package com.stefan.fraud_service.screening.rules

import com.stefan.fraud_service.config.FraudProperties
import com.stefan.fraud_service.screening.model.ScreenCandidate
import com.stefan.fraud_service.screening.model.SenderHistory
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountCeilingRuleTests {
    private val properties = FraudProperties()
    private val rule = AmountCeilingRule(properties)

    @Test
    fun `stays quiet at exactly the ceiling`() {
        assertNull(rule.evaluate(mockCandidate("10000"), emptyHistory()))
    }

    @Test
    fun `fires above the ceiling`() {
        assertEquals("AMOUNT_CEILING", rule.evaluate(mockCandidate("10000.01"), emptyHistory())?.rule)
    }

    @Test
    fun `fires for a sender with no history at all`() {
        assertEquals("AMOUNT_CEILING", rule.evaluate(mockCandidate("50000"), emptyHistory())?.rule)
    }

    @Test
    fun `detail names the amount and the ceiling`() {
        val fired = rule.evaluate(mockCandidate("12000"), emptyHistory())!!
        assertEquals("12000 AUD over 10000", fired.detail)
    }

    private fun mockCandidate(amount: String) = ScreenCandidate(
        transactionId = UUID.randomUUID(),
        senderId = UUID.randomUUID(),
        recipientId = UUID.randomUUID(),
        amount = BigDecimal(amount),
        currency = "AUD",
        screenedAt = Instant.now(),
    )

    private fun emptyHistory() = SenderHistory(0, 0, 1, 0, BigDecimal.ZERO)
}