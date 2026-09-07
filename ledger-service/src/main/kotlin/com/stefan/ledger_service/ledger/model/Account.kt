package com.stefan.ledger_service.ledger.model

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
@Table(name = "accounts")
class Account (
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "account_class", nullable = false, length = 20, updatable = false)
    val accountClass: AccountClass,

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    val currency: String,

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    var balance: BigDecimal,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant? = null,
) {
    fun applyEntry(direction: EntryDirection, amount: BigDecimal) {
        if (direction == accountClass.normalBalance) {
            balance += amount
        } else {
            balance -= amount
        }
    }

    fun canCover(amount: BigDecimal): Boolean {
        return balance >= amount
    }
}