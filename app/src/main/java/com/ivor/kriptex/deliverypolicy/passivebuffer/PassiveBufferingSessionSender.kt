package com.ivor.kriptex.deliverypolicy.passivebuffer

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.outbox.DeliveryAttemptResult
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySession
import com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySessionCompletionSink
import com.ivor.kriptex.deliverypolicy.outbox.session.DeliveryStrategySessionSender
import com.ivor.kriptex.deliverypolicy.outbox.session.InMemoryDeliverySession

/**
 * Passive delivery sender that stores messages into [PassiveDeliveryBuffer] and completes sessions
 * as deferred.
 *
 * This is a transport-agnostic model of store-and-forward: higher layers are responsible for
 * deciding when to drain the buffer and re-attempt delivery.
 */
class PassiveBufferingSessionSender(
    private val buffer: PassiveDeliveryBuffer,
    private val sessionIdGenerator: SessionIdGenerator = IncrementingSessionIdGenerator("ps"),
) : DeliveryStrategySessionSender {

    override fun startSession(
        strategy: DeliveryStrategy,
        message: OutgoingMessage,
        clock: Clock,
        completionSink: DeliverySessionCompletionSink,
    ): DeliverySession {
        val session = InMemoryDeliverySession(
            sessionId = sessionIdGenerator.nextId(),
            messageId = message.messageId,
            clock = clock,
            completionSink = completionSink,
        )

        if (strategy is PassiveDelivery) {
            buffer.enqueue(message, session)
            session.completeDeferred("buffered")
            return session
        }

        session.completeFailed(retryable = true, reason = "not_passive")
        return session
    }
}

interface SessionIdGenerator {
    fun nextId(): String
}

class IncrementingSessionIdGenerator(private val prefix: String) : SessionIdGenerator {
    private var counter: Long = 0L

    override fun nextId(): String {
        counter++
        return "$prefix$counter"
    }
}
