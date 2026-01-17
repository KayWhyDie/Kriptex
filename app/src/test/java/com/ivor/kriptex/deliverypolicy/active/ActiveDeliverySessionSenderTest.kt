package com.ivor.kriptex.deliverypolicy.active

import com.ivor.kriptex.deliverypolicy.ActiveDelivery
import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.diagnostics.DefaultActiveDeliveryDebugTrace
import com.ivor.kriptex.deliverypolicy.decision.DeliveryDecisionEngine
import com.ivor.kriptex.deliverypolicy.outbox.OutboxItem
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.outbox.session.SessionMessageOutbox
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

private class FakeActiveTransport : ActiveTransport {
    enum class Mode {
        IMMEDIATE_SUCCESS,
        IMMEDIATE_TEMP_FAILURE,
        IMMEDIATE_TERMINAL_FAILURE,
        DELAYED_SUCCESS,
    }

    var mode: Mode = Mode.IMMEDIATE_SUCCESS
    var sendCount: Int = 0

    private val pending = LinkedHashMap<String, com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySession>()

    override fun send(message: OutgoingMessage, session: com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySession, clock: Clock) {
        sendCount++
        when (mode) {
            Mode.IMMEDIATE_SUCCESS -> session.completeDelivered()
            Mode.IMMEDIATE_TEMP_FAILURE -> session.completeDeferred("temporary")
            Mode.IMMEDIATE_TERMINAL_FAILURE -> session.completeFailed(retryable = false, reason = "terminal")
            Mode.DELAYED_SUCCESS -> pending[message.messageId] = session
        }
    }

    fun completeDelivered(messageId: String) {
        pending.remove(messageId)?.completeDelivered()
    }
}

class ActiveDeliverySessionSenderTest {
    @Test
    fun immediate_success_removes_message_and_records_trace() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(ActiveDelivery)
        val registry = InMemoryOpenSessionRegistry()
        val trace = DefaultActiveDeliveryDebugTrace()

        val transport = FakeActiveTransport().apply { mode = FakeActiveTransport.Mode.IMMEDIATE_SUCCESS }
        val sender = ActiveDeliverySessionSender(
            transport = transport,
            registry = registry,
            debugTrace = trace,
            sessionIdProvider = IncrementingSessionIdProvider("t"),
        )

        val outbox = SessionMessageOutbox(
            decisionEngine = engine,
            sender = sender,
            clock = clock,
        )

        outbox.enqueue(OutgoingMessage("m1", "c1", byteArrayOf(1), enqueueElapsedMs = 0L))

        assertEquals(1, transport.sendCount)
        assertEquals(0, outbox.snapshot.size)
        assertEquals(0, registry.size)

        val report = trace.dumpDebugReport()
        assertTrue(report.contains("ATTEMPT messageId=m1"))
        assertTrue(report.contains("TRANSPORT messageId=m1"))
        assertTrue(report.contains("SESSION_COMPLETE messageId=m1"))
        assertTrue(report.contains("outcome=DELIVERED"))

        outbox.close()
    }

    @Test
    fun temporary_failure_completes_deferred_and_does_not_open_session() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(ActiveDelivery)
        val registry = InMemoryOpenSessionRegistry()
        val trace = DefaultActiveDeliveryDebugTrace()

        val transport = FakeActiveTransport().apply { mode = FakeActiveTransport.Mode.IMMEDIATE_TEMP_FAILURE }
        val sender = ActiveDeliverySessionSender(
            transport = transport,
            registry = registry,
            debugTrace = trace,
            sessionIdProvider = IncrementingSessionIdProvider("t"),
        )

        val outbox = SessionMessageOutbox(decisionEngine = engine, sender = sender, clock = clock)
        outbox.enqueue(OutgoingMessage("m1", "c1", byteArrayOf(1), enqueueElapsedMs = 0L))

        assertEquals(1, transport.sendCount)
        assertEquals(1, outbox.snapshot.size)
        assertEquals(OutboxItem.Status.DEFERRED, outbox.snapshot.items.single().status)
        assertEquals(0, registry.size)

        val report = trace.dumpDebugReport()
        assertTrue(report.contains("outcome=DEFERRED"))

        outbox.close()
    }

    @Test
    fun delayed_confirmation_keeps_in_flight_then_delivers_later() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(ActiveDelivery)
        val registry = InMemoryOpenSessionRegistry()
        val trace = DefaultActiveDeliveryDebugTrace()

        val transport = FakeActiveTransport().apply { mode = FakeActiveTransport.Mode.DELAYED_SUCCESS }
        val sender = ActiveDeliverySessionSender(
            transport = transport,
            registry = registry,
            debugTrace = trace,
            sessionIdProvider = IncrementingSessionIdProvider("t"),
        )

        val outbox = SessionMessageOutbox(decisionEngine = engine, sender = sender, clock = clock)
        outbox.enqueue(OutgoingMessage("m1", "c1", byteArrayOf(1), enqueueElapsedMs = 0L))

        assertEquals(1, transport.sendCount)
        assertEquals(1, outbox.snapshot.size)
        assertEquals(OutboxItem.Status.IN_FLIGHT, outbox.snapshot.items.single().status)
        assertEquals(1, registry.size)

        clock.advanceBy(20)
        transport.completeDelivered("m1")

        assertEquals(0, outbox.snapshot.size)
        assertEquals(0, registry.size)

        val report = trace.dumpDebugReport()
        assertTrue(report.contains("outcome=DELIVERED"))
        assertTrue(report.contains("durationMs=20"))

        outbox.close()
    }

    @Test
    fun downgrade_while_open_session_completes_as_deferred_and_does_not_duplicate_transport_send() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(ActiveDelivery)
        val registry = InMemoryOpenSessionRegistry()
        val trace = DefaultActiveDeliveryDebugTrace()

        val transport = FakeActiveTransport().apply { mode = FakeActiveTransport.Mode.DELAYED_SUCCESS }
        val sender = ActiveDeliverySessionSender(
            transport = transport,
            registry = registry,
            debugTrace = trace,
            sessionIdProvider = IncrementingSessionIdProvider("t"),
        )

        val downgradeHandler = ActiveDeliveryDowngradeHandler(
            decisionEngine = engine,
            registry = registry,
            clock = clock,
            debugTrace = trace,
        )

        val outbox = SessionMessageOutbox(decisionEngine = engine, sender = sender, clock = clock)
        outbox.enqueue(OutgoingMessage("m1", "c1", byteArrayOf(1), enqueueElapsedMs = 0L))

        assertEquals(1, transport.sendCount)
        assertEquals(OutboxItem.Status.IN_FLIGHT, outbox.snapshot.items.single().status)
        assertEquals(1, registry.size)

        // Downgrade: should defer open session and not create a new attempt.
        engine.emit(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE))

        assertEquals(1, transport.sendCount)
        assertEquals(OutboxItem.Status.DEFERRED, outbox.snapshot.items.single().status)
        assertEquals(0, registry.size)

        val report = trace.dumpDebugReport()
        assertTrue(report.contains("DOWNGRADE_TO_PASSIVE"))
        assertTrue(report.contains("outcome=DEFERRED"))

        outbox.close()
        downgradeHandler.close()
    }
}
