package com.stefan.payment_api_service.exception.transaction

import com.stefan.payment_api_service.exception.ClientError

/**
 * UNDER_REVIEW is a screening outcome, not a state a human assigns. A payment put
 * there by hand would have no decision in fraud-service, so nothing could release it.
 *
 * ClientError because the same request will always be refused the same way, which is
 * what makes it safe to store against an idempotency key and replay.
 */
class ManualReviewStatusException : RuntimeException("UNDER_REVIEW is set by fraud screening, not by hand"), ClientError {
}
