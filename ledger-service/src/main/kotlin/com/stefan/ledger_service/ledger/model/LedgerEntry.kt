package com.stefan.ledger_service.ledger.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.math.BigDecimal
import java.util.UUID
import java.time.Instant
@Entity
@Table(name = "ledger_entries")
class LedgerEntry (
    @Column(name = "transaction_id", nullable = false, updatable = false)
    val transactionId: UUID,

    @Column(name = "account_id", nullable = false, updatable = false)
    val accountId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 6, updatable = false)
    val direction: EntryDirection,

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    val amount: BigDecimal,

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    val currency: String,

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant? = null
)