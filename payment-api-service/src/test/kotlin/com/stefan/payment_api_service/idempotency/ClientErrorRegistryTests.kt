package com.stefan.payment_api_service.idempotency

import com.stefan.payment_api_service.exception.ClientError
import com.stefan.payment_api_service.exception.idempotency.IdempotencyReplayUnavailableException
import com.stefan.payment_api_service.exception.transaction.RecipientNotFoundException
import com.stefan.payment_api_service.exception.transaction.SelfTransferException
import com.stefan.payment_api_service.idempotency.service.ClientErrorReplayer
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter

class ClientErrorRegistryTests {

    private val replayer = ClientErrorReplayer()

    /**
     * The registry is the one hand-maintained list in the idempotency feature.
     * An exception marked ClientError but never registered would be stored as
     * FAILED and then be unreplayable for the rest of its TTL. Scanning for
     * implementations turns that into a build failure instead.
     */
    @Test
    fun `every ClientError can be replayed by the registry`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply {
            addIncludeFilter(AssignableTypeFilter(ClientError::class.java))
        }

        val found: List<BeanDefinition> =
            scanner.findCandidateComponents("com.stefan.payment_api_service").toList()

        assertTrue(found.isNotEmpty(), "scan found no ClientError implementations - check the base package")

        val unregistered = found
            .mapNotNull { it.beanClassName?.substringAfterLast('.') }
            .filterNot { replayer.canReplay(it) }

        assertEquals(
            emptyList<String>(), unregistered,
            "these exceptions are marked ClientError but ClientErrorReplayer cannot rebuild them"
        )
    }

    @Test
    fun `a registered type is rebuilt as its original exception`() {
        assertThrows<SelfTransferException> {
            replayer.rethrow("SelfTransferException", "Cannot send a transaction to yourself")
        }

        val recipient = UUID.randomUUID()
        val rebuilt = assertThrows<RecipientNotFoundException> {
            replayer.rethrow("RecipientNotFoundException", "Recipient $recipient not found")
        }
        assertEquals("Recipient $recipient not found", rebuilt.message)
    }

    @Test
    fun `an unknown type reports that the replay is unavailable, not that a request is in flight`() {
        // Reusing IdempotencyConflictException here would tell the client to keep
        // polling on a record that is already terminal.
        val thrown = assertThrows<IdempotencyReplayUnavailableException> {
            replayer.rethrow("SomeExceptionFromAnOlderDeploy", "whatever it said")
        }
        assertTrue(thrown.message!!.contains("Retry with a new key"))
    }
}
