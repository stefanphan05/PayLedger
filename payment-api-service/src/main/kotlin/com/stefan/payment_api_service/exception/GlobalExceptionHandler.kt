package com.stefan.payment_api_service.exception

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import com.stefan.payment_api_service.service.IdempotencyService.Companion.HEADER_IDEMPOTENCY_KEY
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tools.jackson.core.JacksonException
import tools.jackson.databind.exc.InvalidFormatException

@RestControllerAdvice
class GlobalExceptionHandler: ResponseEntityExceptionHandler() {

    @ExceptionHandler(TransactionNotFoundException::class)
    fun handleTransactionNotFoundException(e: TransactionNotFoundException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)
        problem.title = "Transaction Not Found"
        return problem
    }

    @ExceptionHandler(EmailAlreadyInUseException::class)
    fun handleEmailAlreadyInUseException(e: EmailAlreadyInUseException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message)
        problem.title = "Email Already In Use"
        return problem
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleObjectOptimisticLockingFailureException(e: ObjectOptimisticLockingFailureException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "Transaction was modified concurrently, retry"
        )
        return problem
    }

    @ExceptionHandler(RecipientNotFoundException::class)
    fun handleRecipientNotFoundException(e: RecipientNotFoundException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            e.message
        )
        problem.title = "Recipient Not Found"
        return problem
    }

    @ExceptionHandler(SelfTransferException::class)
    fun handleSelfTransferException(e: SelfTransferException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            e.message
        )
        problem.title = "Invalid Transfer"
        return problem
    }

    @ExceptionHandler(IdempotencyKeyReuseException::class)
    fun handleIdempotencyKeyReuseException(e: IdempotencyKeyReuseException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            e.message
        )
        problem.title = "Idempotency Key Reused"
        return problem
    }

    @ExceptionHandler(IdempotencyConflictException::class)
    fun handleIdempotencyConflictException(e: IdempotencyConflictException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            e.message
        )
        problem.title = "Request In Progress"
        return problem
    }

    @ExceptionHandler(InvalidIdempotencyKeyException::class)
    fun handleInvalidIdempotencyKeyException(e: InvalidIdempotencyKeyException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed")
        problem.title = "Validation Error"
        problem.setProperty("errors", mapOf(HEADER_IDEMPOTENCY_KEY to listOf(e.message)))
        return problem
    }

    // A missing @RequestHeader would otherwise be handled by the inherited
    // ResponseEntityExceptionHandler as a bare 400 with no body detail. Declaring
    // it here wins on specificity and keeps the "errors" shape clients already get
    // from handleMethodArgumentNotValid and handleHttpMessageNotReadable.
    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingRequestHeaderException(e: MissingRequestHeaderException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed")
        problem.title = "Missing Header"
        problem.setProperty("errors", mapOf(e.headerName to listOf("This header is required")))
        return problem
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(e: Exception): ProblemDetail {
        logger.error("Unhandled exception", e)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred"
        )
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthenticationException(e: AuthenticationException): ProblemDetail {
        logger.debug("Authentication failed: ${e.message}")
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password")
        problem.title = "Unauthorized"
        return problem
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDeniedException(e: AccessDeniedException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You are not allowed to do that")
        problem.title = "Forbidden"
        return problem
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val errors = ex.bindingResult.fieldErrors.groupBy(
            { it.field },
            { it.defaultMessage ?: "Invalid value" }
        )

        val problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed")
        problem.title = "Validation Error"
        problem.setProperty("errors", errors)

        return handleExceptionInternal(ex, problem, headers, status, request)
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val problem = ProblemDetail.forStatusAndDetail(status, "Request body is malformed or missing required fields")
        problem.title = "Malformed Request"

        // Jackson fails while constructing the DTO, before @Valid runs, so
        // handleMethodArgumentNotValid never sees these. Report the offending
        // field using the same "errors" shape so clients get one format.
        val cause = ex.cause
        if (cause is JacksonException) {
            val field = cause.path.joinToString(".") { it.propertyName ?: "[${it.index}]" }
            if (field.isNotEmpty()) {
                val message = if (cause is InvalidFormatException) {
                    "'${cause.value}' is not a valid ${cause.targetType.simpleName}"
                } else {
                    "This field is required"
                }
                problem.setProperty("errors", mapOf(field to listOf(message)))
            }
        }

        return handleExceptionInternal(ex, problem, headers, status, request)
    }
}
