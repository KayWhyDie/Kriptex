package com.ivor.kriptex.deliverypolicy.passivebuffer

import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.diagnostics.DefaultPassiveDeliveryBufferDebugTrace
import com.ivor.kriptex.deliverypolicy.decision.DeliveryDecisionEngine
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.outbox.OutboxItem
import com.ivor.kriptex.deliverypolicy.outbox.session.SessionMessageOutbox
import com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySessionCompletionSink
import com.ivor.kriptex.deliverypolicy.outbox.session.InMemoryDeliverySession
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

private class FakeDecisionEngine(initial: com.ivor.kriptex.deliverypolicy.DeliveryStrategy) : DeliveryDecisionEngine {
    private val listeners = LinkedHashSet<(com.ivor.kriptex.deliverypolicy.DeliveryStrategy) -> Unit>()
    private val _flow = MutableStateFlow(initial)

    override val strategy: com.ivor.kriptex.deliverypolicy.DeliveryStrategy
        get() = _flow.value

    override val strategyFlow: StateFlow<com.ivor.kriptex.deliverypolicy.DeliveryStrategy>
        get() = _flow.asStateFlow()

    override fun addListener(listener: (com.ivor.kriptex.deliverypolicy.DeliveryStrategy) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(strategy)
        return { listeners.remove(listener) }
    }

    override fun close() {
        listeners.clear()
    }

    fun emit(newStrategy: com.ivor.kriptex.deliverypolicy.DeliveryStrategy) {
        if (newStrategy == strategy) return
        _flow.value = newStrategy
        listeners.toList().forEach { it(newStrategy) }
    }
}

class PassiveDeliveryBufferTest {
    @Test
    fun enqueue_is_idempotent_and_drain_only_when_available() {
        val clock = FakeClock(0L)
        val trace = DefaultPassiveDeliveryBufferDebugTrace()
        val buffer = InMemoryPassiveDeliveryBuffer(clock = clock, debugTrace = trace)

        val sink = DeliverySessionCompletionSink { _, _, _, _ -> }
        val session = InMemoryDeliverySession("s1", "m1", clock, sink)

        val msg = OutgoingMessage("m1", "c1", byteArrayOf(1, 2), enqueueElapsedMs = 0L)

        assertEquals(BufferEnqueueResult.Enqueued, buffer.enqueue(msg, session))
        assertEquals(BufferEnqueueResult.AlreadyBuffered, buffer.enqueue(msg, session))
        assertEquals(1, buffer.size)

        // Not available yet => no drain.
        assertTrue(buffer.drainReady().isEmpty())
        assertEquals(1, buffer.size)

        buffer.markAvailable()
        val drained = buffer.drainReady()
        assertEquals(1, drained.size)
        assertEquals("m1", drained.single().messageId)
        assertEquals(0, buffer.size)

        val report = trace.dumpDebugReport()
        assertTrue(report.contains("ENQUEUE messageId=m1 sessionId=s1"))
        assertTrue(report.contains("AVAILABLE isAvailable=true"))
        assertTrue(report.contains("RESUME_PLANNED messageId=m1 sessionId=s1"))
        assertTrue(report.contains("DRAIN count=1"))
    }

    @Test
    fun outbox_retry_on_passive_strategy_change_does_not_duplicate_buffer() {
        val clock = FakeClock(0L)
        val buffer = InMemoryPassiveDeliveryBuffer(clock = clock)

        val sender = PassiveBufferingSessionSender(
            buffer = buffer,
            sessionIdGenerator = IncrementingSessionIdGenerator("t"),
        )

        val engine = FakeDecisionEngine(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE))
        val outbox = SessionMessageOutbox(
            decisionEngine = engine,
            sender = sender,
            clock = clock,
        )

        outbox.enqueue(OutgoingMessage("m1", "c1", byteArrayOf(1), enqueueElapsedMs = 0L))

        // Passive sender completes synchronously as DEFERRED.
        assertEquals(1, outbox.snapshot.size)
        assertEquals(OutboxItem.Status.DEFERRED, outbox.snapshot.items.single().status)
        assertEquals(1, buffer.size)

        // Emit a different passive strategy (counts as a strategy change) -> outbox will retry.
        engine.emit(PassiveDelivery(PassiveDelivery.QueueReason.RELAY_ONLY))

        // Buffer must remain idempotent by messageId.
        assertEquals(1, buffer.size)
        assertEquals(OutboxItem.Status.DEFERRED, outbox.snapshot.items.single().status)

        outbox.close()
    }
}
