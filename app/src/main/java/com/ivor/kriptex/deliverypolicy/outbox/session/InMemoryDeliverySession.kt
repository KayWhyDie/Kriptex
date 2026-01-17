package com.ivor.kriptex.deliverypolicy.outbox.session

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.outbox.DeliveryAttemptResult

/**
 * Minimal in-memory [DeliverySession] implementation.
 *
 * It records a monotonic start time and reports a duration on completion.
 * Completing multiple times is ignored.
 */
class InMemoryDeliverySession(
    override val sessionId: String,
    override val messageId: String,
    private val clock: Clock,
    private val completionSink: DeliverySessionCompletionSink,
) : DeliverySession {

    private val startedAtMs: Long = clock.nowMs()
    private var completed: Boolean = false

    override fun completeDelivered() {
        completeOnce(DeliveryAttemptResult.Accepted)
    }

    override fun completeDeferred(reason: String?) {
        completeOnce(DeliveryAttemptResult.Deferred(reason))
    }

    override fun completeFailed(retryable: Boolean, reason: String?) {
        completeOnce(DeliveryAttemptResult.Failed(retryable = retryable, reason = reason))
    }

    private fun completeOnce(result: DeliveryAttemptResult) {
        if (completed) return
        completed = true
        val duration = clock.nowMs() - startedAtMs
        completionSink.onCompleted(
            sessionId = sessionId,
            messageId = messageId,
            result = result,
            durationMs = duration,
        )
    }
}
