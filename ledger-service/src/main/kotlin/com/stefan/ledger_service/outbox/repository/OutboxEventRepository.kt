package com.stefan.ledger_service.outbox.repository

import com.stefan.ledger_service.outbox.model.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface OutboxEventRepository: JpaRepository<OutboxEvent, Long> {
    @Query(
        value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun lockUnpublishedBatch(@Param("limit") limit: Int): List<OutboxEvent>
}