package com.stefan.payment_api_service.outbox.repository

import com.stefan.payment_api_service.outbox.model.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {
    /**
     * The oldest unsent events, claimed for this poller alone.
     *
     * `FOR UPDATE` locks each row it returns, so a second poller cannot pick up the
     * same one. `SKIP LOCKED` makes that second poller step OVER the locked rows and
     * take the next free ones, instead of sitting there blocked behind us. Together
     * they let more than one instance of the app poll at once without any instance
     * sending an event that another already has.
     *
     * The locks last until the surrounding transaction ends - which is why the caller
     * must be @Transactional, and why the poller is split across two beans (see
     * OutboxPoller).
     *
     * Native SQL because JPQL cannot express SKIP LOCKED.
     */
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