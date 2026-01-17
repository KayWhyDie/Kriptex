package com.ivor.kriptex.deliverypolicy.outbox.session

import com.ivor.kriptex.deliverypolicy.ActiveDelivery
import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.diagnostics.DefaultMessageOutboxSessionDebugTrace
import com.ivor.kriptex.deliverypolicy.decision.DeliveryDecisionEngine
import com.ivor.kriptex.deliverypolicy.outbox.OutboxItem
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
    fun advanceBy(deltaMs: Long) {
        now += deltaMs
    }
}

private class FakeDecisionEngine(initial: DeliveryStrategy) : DeliveryDecisionEngine {
    private val listeners = LinkedHashSet<(DeliveryStrategy) -> Unit>()
    private val _flow = MutableStateFlow(initial)

    override val strategy: DeliveryStrategy
        get() = _flow.value

    override val strategyFlow: StateFlow<DeliveryStrategy>
        get() = _flow.asStateFlow()

    override fun addListener(listener: (DeliveryStrategy) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(strategy)
        return { listeners.remove(listener) }
    }

    override fun close() {
        listeners.clear()
    }

    fun emit(newStrategy: DeliveryStrategy) {
        if (newStrategy == strategy) return
        _flow.value = newStrategy
        listeners.toList().forEach { it(newStrategy) }
    }
}

private class HoldingSessionSender : DeliveryStrategySessionSender {
    var lastSession: DeliverySession? = null
    var startCount: Int = 0
    val attempts = mutableListOf<Pair<String, String>>() // (messageId, mode)

    override fun startSession(
        strategy: DeliveryStrategy,
        message: OutgoingMessage,
        clock: Clock,
        completionSink: DeliverySessionCompletionSink,
    ): DeliverySession {
        startCount++
        attempts.add(message.messageId to strategy.mode.name)
        val session = InMemoryDeliverySession(
            sessionId = "s$startCount",
            messageId = message.messageId,
            clock = clock,
            completionSink = completionSink,
        )
        lastSession = session
        return session
    }
}

class SessionMessageOutboxTest {
    @Test
    fun deferred_session_then_later_delivered_removes_message() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(PassiveDelivery(PassiveDelivery.QueueReason.UNKNOWN))
        val sender = HoldingSessionSender()

        val sessionTrace = DefaultMessageOutboxSessionDebugTrace()

        val outbox = SessionMessageOutbox(
            decisionEngine = engine,
            sender = sender,
            clock = clock,
            sessionDebugTrace = sessionTrace,
        )

        outbox.enqueue(OutgoingMessage("m1", "c1", byteArrayOf(1), clock.nowMs()))
        assertEquals(1, sender.startCount)
        assertEquals(OutboxItem.Status.IN_FLIGHT, outbox.snapshot.items.single().status)

        clock.advanceBy(50)
        sender.lastSession!!.completeDeferred("wait")
        assertEquals(OutboxItem.Status.DEFERRED, outbox.snapshot.items.single().status)

        clock.advanceBy(10)
        engine.emit(ActiveDelivery) // triggers retry attempt
        assertEquals(2, sender.startCount)
        assertEquals(OutboxItem.Status.IN_FLIGHT, outbox.snapshot.items.single().status)

        clock.advanceBy(25)
        sender.lastSession!!.completeDelivered()
        assertEquals(0, outbox.snapshot.size)

        val report = sessionTrace.dumpDebugReport()
        assertTrue(report.contains("SESSION_CREATE messageId=m1 sessionId=s1"))
        assertTrue(report.contains("SESSION_COMPLETE messageId=m1 sessionId=s1 outcome=DEFERRED"))
        assertTrue(report.contains("SESSION_CREATE messageId=m1 sessionId=s2"))
        assertTrue(report.contains("SESSION_COMPLETE messageId=m1 sessionId=s2 outcome=DELIVERED"))

        outbox.close()
    }

    @Test
    fun upgrade_during_open_session_does_not_duplicate_attempt() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(PassiveDelivery(PassiveDelivery.QueueReason.UNKNOWN))
        val sender = HoldingSessionSender()

        val outbox = SessionMessageOutbox(
            decisionEngine = engine,
            sender = sender,
            clock = clock,
        )

        outbox.enqueue(OutgoingMessage("m2", "c1", byteArrayOf(2), clock.nowMs()))
        assertEquals(1, sender.startCount)

        // While session is open (IN_FLIGHT), upgrading strategy must not cause a second attempt.
        engine.emit(ActiveDelivery)
        assertEquals(1, sender.startCount)

        // Completing the session as deferred should allow retry on next strategy change.
        sender.lastSession!!.completeDeferred("need_requeue")
        assertEquals(OutboxItem.Status.DEFERRED, outbox.snapshot.items.single().status)

        engine.emit(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE))
        assertEquals(2, sender.startCount)

        outbox.close()
    }

    @Test
    fun failed_retryable_vs_terminal_semantics() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(ActiveDelivery)
        val sender = HoldingSessionSender()

        val outbox = SessionMessageOutbox(
            decisionEngine = engine,
            sender = sender,
            clock = clock,
        )

        outbox.enqueue(OutgoingMessage("m3", "c1", byteArrayOf(3), clock.nowMs()))
        assertEquals(1, sender.startCount)

        sender.lastSession!!.completeFailed(retryable = true, reason = "temp")
        assertEquals(OutboxItem.Status.FAILED_RETRYABLE, outbox.snapshot.items.single().status)

        // Strategy change should retry.
        engine.emit(PassiveDelivery(PassiveDelivery.QueueReason.UNKNOWN))
        assertEquals(2, sender.startCount)

        sender.lastSession!!.completeFailed(retryable = false, reason = "permanent")
        assertEquals(OutboxItem.Status.FAILED_TERMINAL, outbox.snapshot.items.single().status)

        // Further strategy changes should not retry terminal failures.
        engine.emit(ActiveDelivery)
        assertEquals(2, sender.startCount)

        outbox.close()
    }
}
