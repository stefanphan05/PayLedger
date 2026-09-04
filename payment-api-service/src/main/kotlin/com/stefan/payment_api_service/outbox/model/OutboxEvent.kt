package com.stefan.payment_api_service.outbox.model

import com.stefan.payment_api_service.outbox.model.PaymentEventType
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
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // BIGSERIAL
    @Column(name = "id", nullable = false, updatable = false)
    val id: Long = 0,

    @Column(name = "transaction_id", nullable = false, updatable = false)
    val transactionId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    val eventType: PaymentEventType,

    /**
     * The finished envelope JSON. Serialised once, here, at write time - so a retry
     * resends identical bytes, including the same eventId that consumers dedupe on.
     */
    @Column(name = "payload", nullable = false, updatable = false)
    val payload: String,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant? = null,

    /** Null until the broker has acknowledged the record. The only mutable column. */
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
)