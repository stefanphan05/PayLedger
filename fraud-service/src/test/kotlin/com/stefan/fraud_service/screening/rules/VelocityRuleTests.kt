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

class VelocityRuleTests {
    private val properties = FraudProperties()
    private val rule = VelocityRule(properties)

    @Test
    fun `stays quiet at exactly the limit`() {
        // recentCount is what is already written; the candidate makes one more. So four
        // priors is this sender's fifth payment, and maxPayments of 5 allows it.
        assertNull(rule.evaluate(mockCandidate(), mockHistory(recentCount = 4)))
    }

    @Test
    fun `fires one payment over the limit`() {
        // Four priors plus this one is five and allowed, so five priors is the sixth.
        val fired = rule.evaluate(mockCandidate(), mockHistory(recentCount = 5))

        assertEquals("VELOCITY", fired?.rule)
        assertEquals(properties.rules.velocity.weight, fired?.weight)
    }

    @Test
    fun `stays quiet for a sender with no history`() {
        assertNull(rule.evaluate(mockCandidate(), mockHistory(recentCount = 0)))
    }

    @Test
    fun `detail names the count and the window`() {
        val fired = rule.evaluate(mockCandidate(), mockHistory(recentCount = 7))

        // Eight, not seven: seven already written plus the one being screened. This
        // string is what a reviewer reads before releasing someone's money, and it
        // goes into the insights corpus verbatim, so it has to state what happened.
        assertTrue(fired!!.detail.contains("8"), "was '${fired.detail}'")
        assertTrue(fired.detail.contains("60"), "was '${fired.detail}'")
    }
    
    private fun mockCandidate(amount: String = "10.00") = ScreenCandidate(
        transactionId = UUID.randomUUID(),
        senderId = UUID.randomUUID(),
        recipientId = UUID.randomUUID(),
        amount = BigDecimal(amount),
        currency = "AUD",
        screenedAt = Instant.now(),
    )

    private fun mockHistory(
        recentCount: Long = 0,
        distinctRecipients: Long = 0,
        priorToRecipient: Long = 1,
        baselineCount: Long = 0,
        baselineMean: String = "0",
    ) = SenderHistory(
        recentCount = recentCount,
        distinctRecipients = distinctRecipients,
        priorToRecipient = priorToRecipient,
        baselineCount = baselineCount,
        baselineMean = BigDecimal(baselineMean),
    )
}