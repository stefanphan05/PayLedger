package com.stefan.payment_api_service.models.entity

import com.stefan.payment_api_service.models.enum.TransactionStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "transactions")
class Transaction (
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    val amount: BigDecimal,

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    val currency: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var transactionStatus: TransactionStatus = TransactionStatus.PENDING,

    @Column(name = "sender_id", nullable = false, updatable = false)
    val senderId: UUID,

    @Column(name = "recipient_id", nullable = false, updatable = false)
    val recipientId: UUID,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant? = null,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
)