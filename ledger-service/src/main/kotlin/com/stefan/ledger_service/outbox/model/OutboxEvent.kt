package com.stefan.ledger_service.outbox.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "outbox_events")
class OutboxEvent(
    @Column(name = "transaction_id", nullable = false, updatable = false)
    val transactionId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    val eventType: LedgerEventType,

    @Column(name = "payload", nullable = false, updatable = false)
    val payload: String,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    val id: Long = 0,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant? = null,

    @Column(name = "published_at")
    var publishedAt: Instant? = null,
)