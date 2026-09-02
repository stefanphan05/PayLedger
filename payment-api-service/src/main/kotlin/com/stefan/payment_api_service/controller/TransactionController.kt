package com.stefan.payment_api_service.controller

import com.stefan.payment_api_service.models.dto.IdempotencyKeyDTO
import com.stefan.payment_api_service.models.dto.PageResponseDTO
import com.stefan.payment_api_service.models.dto.TransactionRequestDTO
import com.stefan.payment_api_service.models.dto.TransactionResponseDTO
import com.stefan.payment_api_service.models.dto.UpdateTransactionStatusDTO
import com.stefan.payment_api_service.security.RequestHasher
import com.stefan.payment_api_service.security.UserSecurity
import com.stefan.payment_api_service.service.IdempotencyService
import com.stefan.payment_api_service.service.IdempotencyService.Companion.HEADER_IDEMPOTENCY_KEY
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
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/transactions")
class TransactionController(
    private val transactionService: TransactionService,
    private val idempotencyService: IdempotencyService,
    private val requestHasher: RequestHasher,
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

    /**
     * Creates a transaction from the authenticated user to the requested recipient.
     *
     * Requires an `Idempotency-Key` header (8-255 chars, `[A-Za-z0-9_-]`). Keys are
     * scoped to the caller, so two users may pick the same one without colliding.
     *
     * Retrying with the same key replays the original outcome instead of creating a
     * second transaction, for 24 hours. A replayed *success* carries
     * `Idempotent-Replay: true`; a replayed *rejection* looks identical to a fresh
     * one, because errors are rendered by the exception handler, which sets no
     * headers. Informational only - the outcome is the same either way.
     *
     * GOTCHA: the key is bound to the request body it was first used with. A client
     * that *corrects* a rejected request and retries with the same key receives 422,
     * not a fresh attempt - a corrected request needs a NEW key. Bodies differing only
     * in numeric formatting ("50.00" vs "50.0") count as the same request.
     *
     * @return 201 with the transaction; 201 + `Idempotent-Replay` when replayed
     * @throws IdempotencyKeyReuseException 422, key reused with a different body
     * @throws IdempotencyConflictException 409, an earlier request is still in flight
     * @throws InvalidIdempotencyKeyException 400, key missing or malformed
     * @throws SelfTransferException 400, sender and recipient are the same user
     * @throws RecipientNotFoundException 404, no such recipient
     *
     * See docs/decisions/0001-redis-backed-idempotency-keys.md for the design, and
     * ADR-002 for why a rejected request keeps its key.
     */
    @PostMapping
    fun createTransaction(
        @RequestHeader(HEADER_IDEMPOTENCY_KEY) idempotencyKeyHeader: String,
        @RequestBody @Valid transactionRequestDTO: TransactionRequestDTO,
        @AuthenticationPrincipal principal: UserSecurity
    ): ResponseEntity<TransactionResponseDTO> {
        val idempotencyKey = IdempotencyKeyDTO(idempotencyKeyHeader)

        // The first request with this key runs the block below; a retry never
        // reaches it and replays the stored result instead.
        return idempotencyService.execute(
            userId = principal.id,
            key = idempotencyKey.value,
            requestHash = requestHasher.hash(transactionRequestDTO),
            responseType = TransactionResponseDTO::class.java,
        ) {
            val transaction = transactionService.createTransaction(
                transactionRequestDTO,
                principal.id
            )
            ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponseDTO.from(transaction))
        }
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