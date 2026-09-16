package com.stefan.fraud_service.screening.repository

import com.stefan.fraud_service.screening.model.FraudAction
import com.stefan.fraud_service.screening.model.PaymentAttempt
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The projection the five rules read.
 */
interface SenderHistoryRow {
    val recentCount: Long
    val distinctRecipients: Long
    val priorToRecipient: Long
    val baselineCount: Long
    val baselineMean: BigDecimal
}

interface PaymentAttemptRepository: JpaRepository<PaymentAttempt, UUID> {
    @Query(
        value = """
            SELECT
                COUNT(*) FILTER (WHERE screened_at > :velocityFrom)                   AS "recentCount",
                COUNT(DISTINCT recipient_id) FILTER (WHERE screened_at > :fanOutFrom)  AS "distinctRecipients",
                COUNT(*) FILTER (WHERE recipient_id = :recipientId)                    AS "priorToRecipient",
                COUNT(*) FILTER (WHERE screened_at > :baselineFrom)                    AS "baselineCount",
                COALESCE(AVG(amount) FILTER (WHERE screened_at > :baselineFrom), 0)    AS "baselineMean"
            FROM payment_attempts
            WHERE sender_id = :senderId
              AND transaction_id <> :transactionId
        """,
        nativeQuery = true,
    )
    fun historyFor(
        @Param("senderId") senderId: UUID,
        @Param("recipientId") recipientId: UUID?,
        @Param("transactionId") transactionId: UUID,
        @Param("velocityFrom") velocityFrom: Instant,
        @Param("fanOutFrom") fanOutFrom: Instant,
        @Param("baselineFrom") baselineFrom: Instant,
    ): SenderHistoryRow

    fun findAllByDecisionOrderByScreenedAtDesc(
        decision: FraudAction,
        pageable: Pageable,
    ): Page<PaymentAttempt>

    fun findAllBySenderIdOrderByScreenedAtDesc(
        senderId: UUID,
        pageable: Pageable,
    ): Page<PaymentAttempt>
}