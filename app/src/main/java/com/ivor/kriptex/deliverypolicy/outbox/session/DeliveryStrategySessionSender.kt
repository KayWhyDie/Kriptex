package com.ivor.kriptex.deliverypolicy.outbox.session

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage

/**
 * Session-based delivery sender contract.
 *
 * Unlike [com.ivor.kriptex.deliverypolicy.outbox.DeliveryStrategySender], this models a real-world
 * lifecycle where an attempt may be acknowledged asynchronously by completing the returned session.
 */
interface DeliveryStrategySessionSender {
    fun startSession(
        strategy: DeliveryStrategy,
        message: OutgoingMessage,
        clock: Clock,
        completionSink: DeliverySessionCompletionSink,
    ): DeliverySession
}
