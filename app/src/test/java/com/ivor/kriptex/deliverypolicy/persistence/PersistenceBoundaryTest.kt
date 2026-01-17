package com.ivor.kriptex.deliverypolicy.persistence

import com.ivor.kriptex.deliverypolicy.ActiveDelivery
import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.active.ActiveDeliverySessionSender
import com.ivor.kriptex.deliverypolicy.active.ActiveTransport
import com.ivor.kriptex.deliverypolicy.active.InMemoryOpenSessionRegistry
import com.ivor.kriptex.deliverypolicy.active.IncrementingSessionIdProvider
import com.ivor.kriptex.deliverypolicy.decision.DeliveryDecisionEngine
import com.ivor.kriptex.deliverypolicy.diagnostics.DefaultMessageOutboxPersistenceDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.DefaultPassiveDeliveryBufferPersistenceDebugTrace
import com.ivor.kriptex.deliverypolicy.outbox.DefaultMessageOutbox
import com.ivor.kriptex.deliverypolicy.outbox.DeliveryAttemptResult
import com.ivor.kriptex.deliverypolicy.outbox.DeliveryStrategySender
import com.ivor.kriptex.deliverypolicy.outbox.OutboxItem
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.outbox.session.SessionMessageOutbox
import com.ivor.kriptex.deliverypolicy.passivebuffer.InMemoryPassiveDeliveryBuffer
import com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySession
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
        DELAYED_SUCCESS,
    }

    var mode: Mode = Mode.IMMEDIATE_SUCCESS
    var sendCount: Int = 0

    private val pending = LinkedHashMap<String, DeliverySession>()

    override fun send(message: OutgoingMessage, session: DeliverySession, clock: Clock) {
        sendCount++
        when (mode) {
            Mode.IMMEDIATE_SUCCESS -> session.completeDelivered()
            Mode.DELAYED_SUCCESS -> pending[message.messageId] = session
        }
    }

    fun completeDelivered(messageId: String) {
        pending.remove(messageId)?.completeDelivered()
    }
}

class PersistenceBoundaryTest {
    @Test
    fun enqueue_snapshot_restore_then_strategy_upgrade_attempts_once() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE))

        val transport = FakeActiveTransport().apply { mode = FakeActiveTransport.Mode.IMMEDIATE_SUCCESS }
        val registry = InMemoryOpenSessionRegistry()
        val sender = ActiveDeliverySessionSender(
            transport = transport,
            registry = registry,
            sessionIdProvider = IncrementingSessionIdProvider("s"),
        )

        val persistenceTrace1 = DefaultMessageOutboxPersistenceDebugTrace()
        val outbox1 = SessionMessageOutbox(
            decisionEngine = engine,
            sender = sender,
            clock = clock,
            persistenceDebugTrace = persistenceTrace1,
        )

        outbox1.enqueue(OutgoingMessage("m1", "c1", byteArrayOf(1, 2), enqueueElapsedMs = 0L))
        assertEquals(0, transport.sendCount) // passive => no transport
        assertEquals(1, outbox1.snapshot.size)
        assertEquals(OutboxItem.Status.DEFERRED, outbox1.snapshot.items.single().status)

        val persisted = outbox1.snapshot()
        assertEquals(1, persisted.messages.size)

        val report1 = persistenceTrace1.dumpDebugReport()
        assertTrue(report1.contains("SNAPSHOT_BUILT"))

        // Restore into a fresh outbox instance.
        val clock2 = FakeClock(100L)
        val engine2 = FakeDecisionEngine(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE))
        val transport2 = FakeActiveTransport().apply { mode = FakeActiveTransport.Mode.IMMEDIATE_SUCCESS }
        val registry2 = InMemoryOpenSessionRegistry()
        val sender2 = ActiveDeliverySessionSender(
            transport = transport2,
            registry = registry2,
            sessionIdProvider = IncrementingSessionIdProvider("s"),
        )

        val persistenceTrace2 = DefaultMessageOutboxPersistenceDebugTrace()
        val outbox2 = SessionMessageOutbox(
            decisionEngine = engine2,
            sender = sender2,
            clock = clock2,
            persistenceDebugTrace = persistenceTrace2,
        )

        outbox2.restore(persisted)

        assertEquals(0, transport2.sendCount) // restore must not auto-send
        assertEquals(1, outbox2.snapshot.size)
        assertEquals(OutboxItem.Status.DEFERRED, outbox2.snapshot.items.single().status)
        assertEquals(0, registry2.size) // runtime-only state not resurrected

        val report2 = persistenceTrace2.dumpDebugReport()
        assertTrue(report2.contains("RESTORE_APPLIED"))

        // Upgrade triggers attempt.
        engine2.emit(ActiveDelivery)
        assertEquals(1, transport2.sendCount)
        assertEquals(0, outbox2.snapshot.size)

        outbox1.close()
        outbox2.close()
    }

    @Test
    fun snapshot_while_in_flight_restore_resets_to_deferred_and_does_not_send_until_reeval() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(ActiveDelivery)

        val transport = FakeActiveTransport().apply { mode = FakeActiveTransport.Mode.DELAYED_SUCCESS }
        val registry = InMemoryOpenSessionRegistry()
        val sender = ActiveDeliverySessionSender(
            transport = transport,
            registry = registry,
            sessionIdProvider = IncrementingSessionIdProvider("s"),
        )

        val outbox = SessionMessageOutbox(decisionEngine = engine, sender = sender, clock = clock)
        outbox.enqueue(OutgoingMessage("m1", "c1", byteArrayOf(1), enqueueElapsedMs = 0L))

        assertEquals(1, transport.sendCount)
        assertEquals(OutboxItem.Status.IN_FLIGHT, outbox.snapshot.items.single().status)
        assertEquals(1, registry.size)

        val persisted = outbox.snapshot()

        val clock2 = FakeClock(50L)
        val engine2 = FakeDecisionEngine(ActiveDelivery)
        val transport2 = FakeActiveTransport().apply { mode = FakeActiveTransport.Mode.IMMEDIATE_SUCCESS }
        val registry2 = InMemoryOpenSessionRegistry()
        val sender2 = ActiveDeliverySessionSender(
            transport = transport2,
            registry = registry2,
            sessionIdProvider = IncrementingSessionIdProvider("s"),
        )
        val outbox2 = SessionMessageOutbox(decisionEngine = engine2, sender = sender2, clock = clock2)

        outbox2.restore(persisted)

        assertEquals(0, transport2.sendCount) // restore must not auto-send
        assertEquals(OutboxItem.Status.DEFERRED, outbox2.snapshot.items.single().status)
        assertEquals(0, registry2.size)

        // Force a re-evaluation event: passive then active.
        engine2.emit(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE))
        engine2.emit(ActiveDelivery)

        assertEquals(1, transport2.sendCount)
        assertEquals(0, outbox2.snapshot.size)

        outbox.close()
        outbox2.close()
    }

    @Test
    fun passive_buffer_snapshot_restore_resets_availability_and_does_not_duplicate() {
        val clock = FakeClock(0L)
        val persistenceTrace = DefaultPassiveDeliveryBufferPersistenceDebugTrace()
        val buffer1 = InMemoryPassiveDeliveryBuffer(clock = clock, persistenceDebugTrace = persistenceTrace)

        val sink = DeliverySessionCompletionSink { _, _, _, _ -> }
        val session = InMemoryDeliverySession("s1", "m1", clock, sink)

        buffer1.enqueue(OutgoingMessage("m1", "c1", byteArrayOf(1, 2, 3), enqueueElapsedMs = 0L), session)
        assertEquals(1, buffer1.size)

        val snap = buffer1.snapshot()
        assertEquals(1, snap.messages.size)

        val clock2 = FakeClock(100L)
        val buffer2 = InMemoryPassiveDeliveryBuffer(clock = clock2)
        buffer2.restore(snap)

        assertEquals(1, buffer2.size)
        assertTrue(buffer2.drainReady().isEmpty()) // availability resets to unavailable

        buffer2.markAvailable()
        assertEquals(1, buffer2.drainReady().size)
        assertEquals(0, buffer2.size)

        // Restore again should not duplicate.
        buffer2.restore(snap)
        assertEquals(1, buffer2.size)

        val report = persistenceTrace.dumpDebugReport()
        assertTrue(report.contains("SNAPSHOT_BUILT"))

        buffer1.markUnavailable()
    }

    @Test
    fun default_outbox_restore_does_not_attempt_until_strategy_change() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE))

        var attemptCount = 0
        val sender = DeliveryStrategySender { _, _ ->
            attemptCount++
            DeliveryAttemptResult.Deferred("no_transport")
        }

        val trace = DefaultMessageOutboxPersistenceDebugTrace()
        val outbox1 = DefaultMessageOutbox(
            decisionEngine = engine,
            sender = sender,
            clock = clock,
            persistenceDebugTrace = trace,
        )

        outbox1.enqueue(OutgoingMessage("m1", "c1", byteArrayOf(1), enqueueElapsedMs = 0L))
        val snap = outbox1.snapshot()

        val clock2 = FakeClock(10L)
        val engine2 = FakeDecisionEngine(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE))
        attemptCount = 0
        val outbox2 = DefaultMessageOutbox(
            decisionEngine = engine2,
            sender = sender,
            clock = clock2,
        )

        outbox2.restore(snap)
        assertEquals(0, attemptCount)
        assertEquals(OutboxItem.Status.DEFERRED, outbox2.snapshot.items.single().status)

        engine2.emit(ActiveDelivery)
        assertTrue(attemptCount >= 1)

        outbox1.close()
        outbox2.close()
    }
}
