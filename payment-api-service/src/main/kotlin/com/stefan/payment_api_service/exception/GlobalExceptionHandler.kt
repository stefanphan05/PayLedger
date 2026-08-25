package com.stefan.payment_api_service.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
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
}