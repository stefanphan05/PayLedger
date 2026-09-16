package com.stefan.fraud_service.screening.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "payment_attempts")
class PaymentAttempt(
    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    val transactionId: UUID,

    @Column(name = "sender_id", nullable = false, updatable = false)
    val senderId: UUID,

    @Column(name = "recipient_id", nullable = false, updatable = false)
    val recipientId: UUID,

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    val amount: BigDecimal,

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    val currency: String,

    @Column(name = "screened_at", nullable = false, updatable = false)
    val screenedAt: Instant,

    @Column(name = "correlation_id", length = 64, nullable = false, updatable = false)
    val correlationId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false)
    var decision: FraudAction,

    @Column(name = "score", nullable = false)
    val score: Int,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "triggered_rules", nullable = false)
    val triggeredRules: String,

    @Column(name = "overridden_at")
    var overriddenAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "overridden_to")
    var overriddenTo: FraudAction? = null,
)