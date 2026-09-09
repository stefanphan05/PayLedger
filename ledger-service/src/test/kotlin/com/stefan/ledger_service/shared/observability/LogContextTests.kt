package com.stefan.ledger_service.shared.observability

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Where a correlation id comes from when an event arrives.
 *
 * Two things are being pinned here. One, nothing a stranger sends us reaches the log
 * stream unchecked. Two, whatever this returns is written to a column that only holds
 * 64 characters, so an id this lets through and the database refuses would cost us the
 * event - the insert fails, the message is retried, and eventually dropped. This
 * service is where that hurts most: its consumer writes an outbox row of its own for
 * every payment it applies.
 *
 * No Spring, no containers. This is string handling.
 */
class LogContextTests {

    @Test
    fun `a well formed header is used as is`() {
        assertEquals("demo-1", LogContext.of(record(header = "demo-1")))
    }

    @Test
    fun `a missing header falls back to the record key`() {
        // The key is the transaction id, which is what you would have searched for
        // anyway. Events published before this feature existed have no header.
        assertEquals("txn-key", LogContext.of(record(key = "txn-key")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["with space", "quote\"", "semi;colon", "line\nbreak", "   "])
    fun `a header that is not a plain id is rejected`(header: String) {
        assertEquals("txn-key", LogContext.of(record(header = header, key = "txn-key")))
    }

    @Test
    fun `a header longer than the column is rejected`() {
        val tooLong = "a".repeat(LogContext.MAX_LENGTH + 1)

        assertEquals("txn-key", LogContext.of(record(header = tooLong, key = "txn-key")))
    }

    @Test
    fun `a key too long for the column is rejected too`() {
        // The fallback gets the same check as the header. Skipping it here is how an
        // oversized value reaches the insert and costs us the event.
        val tooLong = "a".repeat(LogContext.MAX_LENGTH + 1)

        assertEquals("unknown", LogContext.of(record(key = tooLong)))
    }

    @Test
    fun `a record with no header and no key is unknown`() {
        assertEquals("unknown", LogContext.of(record(key = null)))
    }

    private fun record(
        header: String? = null,
        key: String? = "txn-key",
    ): ConsumerRecord<String, String> {
        val record = ConsumerRecord<String, String>("payment-events", 0, 0L, key, "{}")

        if (header != null) {
            record.headers().add(LogContext.HEADER, header.toByteArray(Charsets.UTF_8))
        }

        return record
    }
}
