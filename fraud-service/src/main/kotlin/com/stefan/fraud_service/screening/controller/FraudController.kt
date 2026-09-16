package com.stefan.fraud_service.screening.controller

import com.stefan.fraud_service.exception.DecisionNotFoundException
import com.stefan.fraud_service.screening.model.DecisionResponseDTO
import com.stefan.fraud_service.screening.model.FraudAction
import com.stefan.fraud_service.screening.repository.PaymentAttemptRepository
import com.stefan.fraud_service.screening.service.ReviewService
import com.stefan.fraud_service.shared.PageResponseDTO
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@RestController
@RequestMapping("/fraud")
class FraudController(
    private val attempts: PaymentAttemptRepository,
    private val reviewService: ReviewService,
    private val jsonMapper: JsonMapper,
) {
    @GetMapping("/decisions")
    fun decisions(
        @RequestParam(required = false) decision: FraudAction?,
        @RequestParam(required = false) senderId: UUID?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<PageResponseDTO<DecisionResponseDTO>> {
        val page = when {
            decision != null -> attempts.findAllByDecisionOrderByScreenedAtDesc(decision, pageable)
            senderId != null -> attempts.findAllBySenderIdOrderByScreenedAtDesc(senderId, pageable)
            else -> attempts.findAll(pageable)
        }

        return ResponseEntity.ok(
            PageResponseDTO.from(page) { DecisionResponseDTO.from(it, jsonMapper) }
        )
    }

    @GetMapping("/decisions/{transactionId}")
    fun decision(@PathVariable transactionId: UUID): ResponseEntity<DecisionResponseDTO> {
        val attempt = attempts.findById(transactionId)
            .orElseThrow { DecisionNotFoundException(transactionId) }

        return ResponseEntity.ok(DecisionResponseDTO.from(attempt, jsonMapper))
    }

    /**
     * Let a held payment through. Publishes PAYMENT_CLEARED
     */
    @PostMapping("/decisions/{transactionId}/release")
    fun release(
        @PathVariable transactionId: UUID,
        @AuthenticationPrincipal actor: UUID,
    ): ResponseEntity<DecisionResponseDTO> {
        val attempt = reviewService.release(transactionId, actor.toString())
        return ResponseEntity.ok(DecisionResponseDTO.from(attempt, jsonMapper))
    }

    @PostMapping("/decisions/{transactionId}/reject")
    fun reject(
        @PathVariable transactionId: UUID,
        @AuthenticationPrincipal actor: UUID,
    ): ResponseEntity<DecisionResponseDTO> {
        val attempt = reviewService.reject(transactionId, actor.toString())
        return ResponseEntity.ok(DecisionResponseDTO.from(attempt, jsonMapper))
    }
}