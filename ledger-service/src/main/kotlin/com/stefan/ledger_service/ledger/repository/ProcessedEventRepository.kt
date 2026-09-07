package com.stefan.ledger_service.ledger.repository

import com.stefan.ledger_service.ledger.model.ProcessedEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ProcessedEventRepository: JpaRepository<ProcessedEvent, UUID> {

    /**
     * Claims an event id, or fails because another delivery already has it.
     *
     * A plain INSERT rather than save() or EntityManager.persist(), for two reasons.
     *
     * save() would be wrong: the id is assigned, never generated, so Spring Data
     * treats the entity as detached and calls merge() - which SELECTs and then
     * UPDATEs the existing row on a replay. No violation, no guard, applied twice.
     *
     * persist() would be right but throws Hibernate's ConstraintViolationException.
     * Spring only translates persistence exceptions at a @Repository boundary, so
     * calling it from a @Service leaks the raw Hibernate type and no
     * DataIntegrityViolationException catch upstream would ever match. Going through
     * the repository proxy means the caller sees Spring's exception.
     *
     * The statement executes immediately, so the violation surfaces here rather than
     * at commit, where nothing is catching it.
     */
    @Modifying
    @Query(
        value = "INSERT INTO processed_events (event_id, transaction_id) VALUES (:eventId, :transactionId)",
        nativeQuery = true,
    )
    fun record(@Param("eventId") eventId: UUID, @Param("transactionId") transactionId: UUID)
}
