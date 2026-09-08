package com.stefan.payment_api_service.ledger.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "processed_events")
class ProcessedEvent (
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,

    @Column(name = "transaction_id", nullable = false, updatable = false)
    val transactionId: UUID,

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    val processedAt: Instant? = null,
)