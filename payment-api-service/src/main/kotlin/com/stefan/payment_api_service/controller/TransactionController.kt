package com.stefan.payment_api_service.controller

import com.stefan.payment_api_service.models.dto.TransactionRequestDTO
import com.stefan.payment_api_service.models.entity.Transaction
import com.stefan.payment_api_service.models.exception.TransactionNotFoundException
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
        return try {
            ResponseEntity.ok(transactionService.getTransactionById(transactionId))
        } catch (ex: TransactionNotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.message)
        }
    }

    @PostMapping
    fun createTransaction(@RequestBody @Valid transactionRequestDTO: TransactionRequestDTO): ResponseEntity<Any> {
        return try {
            val created = transactionService.createTransaction(transactionRequestDTO)
            ResponseEntity.status(HttpStatus.CREATED).body(created)
        } catch (ex: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.message)
        }
    }
}