package com.stefan.payment_api_service.repository

import com.stefan.payment_api_service.models.entity.Transaction
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.UUID

@Repository
interface TransactionRepository: JpaRepository<Transaction, UUID> {
    @Query("SELECT t FROM Transaction t WHERE t.id = :id AND (t.senderId = :userId OR t.recipientId = :userId)")
    fun findByIDAndParty(@Param("id") id: UUID, @Param("userId") userId: UUID): Optional<Transaction>

    @Query("SELECT t FROM Transaction t WHERE t.senderId = :userId or t.recipientId = :userId")
    fun findAllByParty(@Param("userId") userId: UUID, pageable: Pageable): Page<Transaction>
}
