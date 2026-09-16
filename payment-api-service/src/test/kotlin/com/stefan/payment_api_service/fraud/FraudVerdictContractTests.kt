package com.stefan.payment_api_service.fraud

import com.stefan.payment_api_service.fraud.model.FraudEventEnvelope
import com.stefan.payment_api_service.fraud.model.FraudVerdict
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FraudVerdictContractTests {

    private val jsonMapper: JsonMapper = jacksonMapperBuilder().build()

    private val contract: String =
        FraudVerdictContractTests::class.java
            .getResource("/contract/fraud-verdict.json")!!
            .readText()

    @Test
    fun `payment-api can read a fraud verdict`() {
        val envelope = jsonMapper.readValue(contract, FraudEventEnvelope::class.java)

        assertNotNull(envelope.eventId)
        assertNotNull(envelope.transactionId)
    }

    @Test
    fun `the payload and decision this service ignores do not break it`() {
        jsonMapper.readValue(contract, FraudEventEnvelope::class.java)
    }

    @Test
    fun `every event type the topic carries maps to a verdict`() {
        assertEquals(FraudVerdict.CLEARED, FraudVerdict.of("PAYMENT_CLEARED"))
        assertEquals(FraudVerdict.HELD, FraudVerdict.of("PAYMENT_HELD"))
        assertEquals(FraudVerdict.BLOCKED, FraudVerdict.of("PAYMENT_BLOCKED"))
        assertEquals(null, FraudVerdict.of("PAYMENT_INITIATED"))
    }
}