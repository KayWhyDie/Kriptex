package com.ivor.kriptex.deliverypolicy.outbox.session

import com.ivor.kriptex.deliverypolicy.outbox.DeliveryAttemptResult

/**
 * Represents one delivery attempt for one message.
 *
 * Sessions may complete synchronously or be held open for later completion.
 * Implementations MUST be idempotent in the sense that completing twice is ignored.
 */
interface DeliverySession {
    val sessionId: String
    val messageId: String

    fun completeDelivered()

    fun completeDeferred(reason: String? = null)

    fun completeFailed(retryable: Boolean, reason: String? = null)
}

/** Completion sink used by a [DeliverySession] to report its final outcome. */
fun interface DeliverySessionCompletionSink {
    fun onCompleted(
        sessionId: String,
        messageId: String,
        result: DeliveryAttemptResult,
        durationMs: Long,
    )
}
