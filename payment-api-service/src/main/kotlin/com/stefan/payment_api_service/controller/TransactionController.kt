package com.stefan.payment_api_service.controller

import com.stefan.payment_api_service.models.dto.TransactionRequestDTO
import com.stefan.payment_api_service.models.dto.TransactionResponseDTO
import com.stefan.payment_api_service.service.TransactionService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
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
    fun transaction(@PathVariable transactionId: UUID): ResponseEntity<Any> {
        val transaction = transactionService.getTransactionById(transactionId)
        return ResponseEntity.ok(TransactionResponseDTO.from(transaction))
    }

    @PostMapping
    fun createTransaction(@RequestBody @Valid transactionRequestDTO: TransactionRequestDTO): ResponseEntity<Any> {
        val transaction = transactionService.createTransaction(transactionRequestDTO)
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponseDTO.from(transaction))
    }
}