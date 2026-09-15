package com.stefan.payment_api_service.fraud

import com.stefan.payment_api_service.fraud.model.FraudVerdict
import com.stefan.payment_api_service.transaction.model.TransactionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FraudVerdictTests {
    @Test
    fun `a hold only applies to a pending payment`() {
        assertTrue(TransactionStatus.PENDING in FraudVerdict.HELD.appliesTo)
        assertFalse(TransactionStatus.COMPLETED in FraudVerdict.HELD.appliesTo)
        assertEquals(TransactionStatus.UNDER_REVIEW, FraudVerdict.HELD.newStatus)
    }

    @Test
    fun `a clearance never touches a settled payment`() {
        assertFalse(TransactionStatus.COMPLETED in FraudVerdict.CLEARED.appliesTo)
        assertFalse(TransactionStatus.FAILED in FraudVerdict.CLEARED.appliesTo)
        assertEquals(setOf(TransactionStatus.UNDER_REVIEW), FraudVerdict.CLEARED.appliesTo)
    }

    @Test
    fun `a block is terminal and says why`() {
        assertEquals(TransactionStatus.FAILED, FraudVerdict.BLOCKED.newStatus)
        assertEquals("FRAUD_BLOCKED", FraudVerdict.BLOCKED.failureReason)
    }

    @Test
    fun `no reason is attached to a clearance or a hold`() {
        assertEquals(null, FraudVerdict.CLEARED.failureReason)
        assertEquals(null, FraudVerdict.HELD.failureReason)
    }
}