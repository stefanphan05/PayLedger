package com.stefan.ledger_service.outbox.service

import com.stefan.ledger_service.ledger.model.RejectionReason
import com.stefan.ledger_service.outbox.model.LedgerEventEnvelope
import com.stefan.ledger_service.outbox.model.LedgerEventType
import com.stefan.ledger_service.outbox.model.OutboxEvent
import com.stefan.ledger_service.outbox.repository.OutboxEventRepository
import com.stefan.ledger_service.shared.observability.LogContext
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Service
class LedgerEventPublisher(
    private val repository: OutboxEventRepository,
    private val jsonMapper: JsonMapper,
) {
    fun publish(
        eventType: LedgerEventType,
        transactionId: UUID,
        reason: RejectionReason? = null,
    ) {
        val envelope = LedgerEventEnvelope.of(eventType, transactionId, reason)

        repository.save(
            OutboxEvent(
                transactionId = transactionId,
                eventType = eventType,
                payload = jsonMapper.writeValueAsString(envelope),
                correlationId = LogContext.current() ?: transactionId.toString()
            )
        )
    }
}