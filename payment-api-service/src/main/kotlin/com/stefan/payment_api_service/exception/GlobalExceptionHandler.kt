package com.stefan.payment_api_service.exception

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

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
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You are not allow to do that")
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
}