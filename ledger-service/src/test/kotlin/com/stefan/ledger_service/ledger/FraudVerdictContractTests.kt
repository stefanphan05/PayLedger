package com.stefan.ledger_service.ledger

import com.stefan.ledger_service.ledger.consumer.model.PaymentEventEnvelope
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.math.BigDecimal
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
    fun `the ledger can read a fraud verdict with its existing DTOs`() {
        val envelope = jsonMapper.readValue(contract, PaymentEventEnvelope::class.java)

        assertNotNull(envelope.eventId)
        assertNotNull(envelope.transactionId)
        assertNotNull(envelope.occurredAt)
    }

    @Test
    fun `every field the transfer needs survives the trip`() {
        val payload = jsonMapper.readValue(contract, PaymentEventEnvelope::class.java).payload

        assertNotNull(payload.transactionId)
        assertNotNull(payload.senderId)
        assertNotNull(payload.recipientId)
        assertEquals("AUD", payload.currency)
        assertEquals(0, BigDecimal("1200.00").compareTo(payload.amount))
    }

    @Test
    fun `the decision the ledger does not care about is ignored`() {
        jsonMapper.readValue(contract, PaymentEventEnvelope::class.java)
    }
}