package com.ivor.kriptex.deliverypolicy.active

import com.ivor.kriptex.deliverypolicy.ActiveDelivery
import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.diagnostics.ActiveDeliveryDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpActiveDeliveryDebugTrace
import com.ivor.kriptex.deliverypolicy.outbox.DeliveryAttemptResult
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySession
import com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySessionCompletionSink
import com.ivor.kriptex.deliverypolicy.outbox.session.DeliveryStrategySessionSender
import com.ivor.kriptex.deliverypolicy.outbox.session.InMemoryDeliverySession

/**
 * Session-based active delivery sender.
 *
 * It models real-time behavior without transport:
 * - starts a [DeliverySession]
 * - invokes an [ActiveTransport] hook which completes the session (now or later)
 *
 * It never enqueues into passive buffers.
 */
class ActiveDeliverySessionSender(
    private val transport: ActiveTransport,
    private val registry: OpenSessionRegistry = NoOpOpenSessionRegistry,
    private val debugTrace: ActiveDeliveryDebugTrace = NoOpActiveDeliveryDebugTrace,
    private val sessionIdProvider: SessionIdProvider = IncrementingSessionIdProvider("as"),
) : DeliveryStrategySessionSender {

    override fun startSession(
        strategy: DeliveryStrategy,
        message: OutgoingMessage,
        clock: Clock,
        completionSink: DeliverySessionCompletionSink,
    ): DeliverySession {
        val sessionId = sessionIdProvider.nextId()

        val wrappedSink = DeliverySessionCompletionSink { completedSessionId, messageId, result, durationMs ->
            val outcome = when (result) {
                DeliveryAttemptResult.Accepted -> "DELIVERED"
                is DeliveryAttemptResult.Deferred -> "DEFERRED"
                is DeliveryAttemptResult.Failed -> if (result.retryable) "FAILED_RETRYABLE" else "FAILED_TERMINAL"
            }
            debugTrace.onSessionCompleted(
                messageId = messageId,
                sessionId = completedSessionId,
                outcome = outcome,
                durationMs = durationMs,
                elapsedMs = clock.nowMs(),
            )
            registry.unregister(completedSessionId)
            completionSink.onCompleted(completedSessionId, messageId, result, durationMs)
        }

        val session = InMemoryDeliverySession(
            sessionId = sessionId,
            messageId = message.messageId,
            clock = clock,
            completionSink = wrappedSink,
        )

        if (strategy != ActiveDelivery) {
            // If the outbox calls us while not in Active strategy (e.g., during a downgrade),
            // do not invoke transport and do not turn the message into a failure state.
            session.completeDeferred("not_active")
            return session
        }

        registry.register(session)
        debugTrace.onAttempt(messageId = message.messageId, sessionId = sessionId, elapsedMs = clock.nowMs())
        debugTrace.onTransportInvoked(messageId = message.messageId, sessionId = sessionId, elapsedMs = clock.nowMs())

        transport.send(message = message, session = session, clock = clock)
        return session
    }
}

interface SessionIdProvider {
    fun nextId(): String
}

class IncrementingSessionIdProvider(private val prefix: String) : SessionIdProvider {
    private var counter: Long = 0L

    override fun nextId(): String {
        counter++
        return "$prefix$counter"
    }
}
