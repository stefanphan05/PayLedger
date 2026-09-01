package com.stefan.payment_api_service.controller

import com.stefan.payment_api_service.models.dto.PageResponseDTO
import com.stefan.payment_api_service.models.dto.TransactionRequestDTO
import com.stefan.payment_api_service.models.dto.TransactionResponseDTO
import com.stefan.payment_api_service.models.dto.UpdateTransactionStatusDTO
import com.stefan.payment_api_service.security.UserSecurity
import com.stefan.payment_api_service.service.TransactionService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/transactions")
class TransactionController(
    private val transactionService: TransactionService,
) {
    @GetMapping("/{transactionId}")
    fun transaction(@PathVariable transactionId: UUID, @AuthenticationPrincipal principal: UserSecurity): ResponseEntity<TransactionResponseDTO> {
        val transaction = transactionService.getTransactionForRequester(
            transactionId,
            principal
        )
        return ResponseEntity.ok(TransactionResponseDTO.from(transaction))
    }

    @GetMapping
    fun getTransactions(
        @AuthenticationPrincipal principal: UserSecurity,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable
    ): ResponseEntity<PageResponseDTO<TransactionResponseDTO>> {
        val transactions = transactionService.getTransactionForUser(
            principal.id,
            pageable
        )

        return ResponseEntity.ok(PageResponseDTO.from(transactions, TransactionResponseDTO::from))
    }

    @PostMapping
    fun createTransaction(@RequestBody @Valid transactionRequestDTO: TransactionRequestDTO, @AuthenticationPrincipal principal: UserSecurity): ResponseEntity<TransactionResponseDTO> {
        val transaction = transactionService.createTransaction(
            transactionRequestDTO,
            principal.id
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponseDTO.from(transaction))
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{transactionId}/status")
    fun updateStatus(
        @PathVariable transactionId: UUID,
        @RequestBody @Valid updateTransactionStatusDTO: UpdateTransactionStatusDTO,
    ): ResponseEntity<TransactionResponseDTO> {
        val transaction = transactionService.updateTransactionStatus(transactionId, updateTransactionStatusDTO.status)
        return ResponseEntity.ok(TransactionResponseDTO.from(transaction))
    }
}