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

class NewRecipientLargeRuleTests {
    private val properties = FraudProperties()
    private val rule = NewRecipientLargeRule(properties)

    @Test
    fun `fires for a large first payment to someone new`() {
        val fired = rule.evaluate(mockCandidate("1000.01"), mockHistory(priorToRecipient = 0))
        assertEquals("NEW_RECIPIENT_LARGE", fired?.rule)
    }

    @Test
    fun `stays quiet when the sender has paid this recipient before`() {
        assertNull(rule.evaluate(mockCandidate("50000"), mockHistory(priorToRecipient = 1)))
    }

    @Test
    fun `stays quiet at exactly the minimum amount`() {
        assertNull(rule.evaluate(mockCandidate("1000"), mockHistory(priorToRecipient = 0)))
    }

    @Test
    fun `cannot reach review on its own`() {
        val fired = rule.evaluate(mockCandidate("5000"), mockHistory(priorToRecipient = 0))!!
        assertTrue(
            fired.weight < properties.reviewThreshold,
            "weight ${fired.weight} would hold a legitimate first payment",
        )
    }

    private fun mockCandidate(amount: String) = ScreenCandidate(
        transactionId = UUID.randomUUID(),
        senderId = UUID.randomUUID(),
        recipientId = UUID.randomUUID(),
        amount = BigDecimal(amount),
        currency = "AUD",
        screenedAt = Instant.now(),
    )

    private fun mockHistory(priorToRecipient: Long) =
        SenderHistory(0, 0, priorToRecipient, 0, BigDecimal.ZERO)
}