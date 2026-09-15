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
import kotlin.test.assertTrue

class AmountAnomalyRuleTests {
    private val properties = FraudProperties()
    private val rule = AmountAnomalyRule(properties)

    @Test
    fun `stays quiet for a payment at the sender's normal size`() {
        assertNull(rule.evaluate(mockCandidate("100"), mockHistory(baselineCount = 20, baselineMean = "100")))
    }

    @Test
    fun `stays quiet at exactly the multiplier`() {
        // multiplier is 10, mean is 100, so 1000 is the boundary and must not fire.
        assertNull(rule.evaluate(mockCandidate("1000"), mockHistory(baselineCount = 20, baselineMean = "100")))
    }

    @Test
    fun `fires above the multiplier`() {
        val fired = rule.evaluate(mockCandidate("1000.01"), mockHistory(baselineCount = 20, baselineMean = "100"))
        assertEquals("AMOUNT_ANOMALY", fired?.rule)
    }

    @Test
    fun `stays quiet below the minimum history`() {
        assertNull(rule.evaluate(mockCandidate("100000"), mockHistory(baselineCount = 4, baselineMean = "100")))
    }

    @Test
    fun `stays quiet when the mean is zero`() {
        assertNull(rule.evaluate(mockCandidate("100"), mockHistory(baselineCount = 20, baselineMean = "0")))
    }

    @Test
    fun `detail names the ratio`() {
        val fired = rule.evaluate(mockCandidate("2400"), mockHistory(baselineCount = 20, baselineMean = "100"))!!
        assertTrue(fired.detail.contains("24"), "was '${fired.detail}'")
    }

    private fun mockCandidate(amount: String) = ScreenCandidate(
        transactionId = UUID.randomUUID(),
        senderId = UUID.randomUUID(),
        recipientId = UUID.randomUUID(),
        amount = BigDecimal(amount),
        currency = "AUD",
        screenedAt = Instant.now(),
    )

    private fun mockHistory(baselineCount: Long, baselineMean: String) =
        SenderHistory(0, 0, 1, baselineCount, BigDecimal(baselineMean))
}