package com.ivor.kriptex.deliverypolicy.outbox

sealed interface DeliveryAttemptResult {
    data object Accepted : DeliveryAttemptResult

    /** Strategy chose not to send right now (e.g., passive mode queueing). */
    data class Deferred(val reason: String? = null) : DeliveryAttemptResult

    /** Strategy rejected the attempt. If [retryable] is true, outbox may retry later. */
    data class Failed(val retryable: Boolean, val reason: String? = null) : DeliveryAttemptResult
}
