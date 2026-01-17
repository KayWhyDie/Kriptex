package com.ivor.kriptex.deliverypolicy.active

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySession

/**
 * Hook for modeling an "active" real-time send without implementing transport.
 *
 * Implementations are expected to complete the [session] explicitly:
 * - immediately, or
 * - later (delayed confirmation)
 */
fun interface ActiveTransport {
    fun send(message: OutgoingMessage, session: DeliverySession, clock: Clock)
}
