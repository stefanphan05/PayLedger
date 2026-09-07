package com.stefan.ledger_service.ledger.repository

import com.stefan.ledger_service.ledger.model.ProcessedEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProcessedEventRepository: JpaRepository<ProcessedEvent, UUID> {
}