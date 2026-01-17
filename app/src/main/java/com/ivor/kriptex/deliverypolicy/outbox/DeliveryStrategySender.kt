package com.ivor.kriptex.deliverypolicy.outbox

import com.ivor.kriptex.deliverypolicy.DeliveryStrategy

/**
 * Transport adapter.
 *
 * The outbox never performs networking; it delegates an attempt to a sender.
 * Implementations are expected to be fast/non-blocking.
 */
fun interface DeliveryStrategySender {
    fun attemptSend(strategy: DeliveryStrategy, message: OutgoingMessage): DeliveryAttemptResult
}
