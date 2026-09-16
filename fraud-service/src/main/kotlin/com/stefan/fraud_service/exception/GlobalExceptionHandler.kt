package com.stefan.fraud_service.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(DecisionNotFoundException::class)
    fun handleDecisionNotFound(e: DecisionNotFoundException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)
        problem.title = "Decision Not Found"
        return problem
    }

    @ExceptionHandler(ReviewConflictException::class)
    fun handleReviewConflict(e: ReviewConflictException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message)
        problem.title = "Review Already Decided"
        return problem
    }
}