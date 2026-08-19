package com.stefan.payment_api_service.repository

import com.stefan.payment_api_service.models.entity.Transaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TransactionRepository: JpaRepository<Transaction, UUID> {

}