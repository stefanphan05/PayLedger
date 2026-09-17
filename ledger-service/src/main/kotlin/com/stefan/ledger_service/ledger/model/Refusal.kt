package com.stefan.ledger_service.ledger.model

/**
 * Why a payment was refused, in both forms:
 * - a code for the system to record
 * - a sentence for the person reading it
 */
data class Refusal(
    val reason: RejectionReason,
    val detail: String = reason.message,
)
