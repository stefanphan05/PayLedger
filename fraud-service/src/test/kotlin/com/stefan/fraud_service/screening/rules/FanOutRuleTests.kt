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

class FanOutRuleTests {
    private val properties = FraudProperties()
    private val rule = FanOutRule(properties)

    @Test
    fun `stays quiet at exactly the recipient limit`() {
        assertNull(rule.evaluate(mockCandidate(), mockHistory(distinctRecipients = 4)))
    }

    @Test
    fun `fires one recipient over the limit`() {
        assertEquals("FAN_OUT", rule.evaluate(mockCandidate(), mockHistory(distinctRecipients = 5))?.rule)
    }
    
    @Test
    fun `combined with velocity it reaches the block threshold`() {
        val fanOut = properties.rules.fanOut.weight
        val velocity = properties.rules.velocity.weight

        assertTrue(
            fanOut + velocity >= properties.blockThreshold,
            "$fanOut + $velocity no longer blocks the drain pattern",
        )
    }

    private fun mockCandidate() = ScreenCandidate(
        transactionId = UUID.randomUUID(),
        senderId = UUID.randomUUID(),
        recipientId = UUID.randomUUID(),
        amount = BigDecimal("10.00"),
        currency = "AUD",
        screenedAt = Instant.now(),
    )

    private fun mockHistory(distinctRecipients: Long) =
        SenderHistory(0, distinctRecipients, 1, 0, BigDecimal.ZERO)
}