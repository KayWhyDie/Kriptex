package com.ivor.kriptex.deliverypolicy.outbox

import com.ivor.kriptex.deliverypolicy.ActiveDelivery
import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.diagnostics.DefaultMessageOutboxDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.MessageOutboxDebugTrace
import com.ivor.kriptex.deliverypolicy.decision.DeliveryDecisionEngine
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

private class RecordingSender(
    private val behavior: (DeliveryStrategy, OutgoingMessage) -> DeliveryAttemptResult,
) : DeliveryStrategySender {
    val attempts = mutableListOf<Pair<String, String>>() // (messageId, mode)

    override fun attemptSend(strategy: DeliveryStrategy, message: OutgoingMessage): DeliveryAttemptResult {
        attempts.add(message.messageId to strategy.mode.name)
        return behavior(strategy, message)
    }
}

class MessageOutboxTest {
    @Test
    fun enqueue_before_realtime_available_is_deferred_then_retried_on_upgrade_without_duplication() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(PassiveDelivery(PassiveDelivery.QueueReason.UNKNOWN))

        val sender = RecordingSender { strategy, _ ->
            when (strategy) {
                is ActiveDelivery -> DeliveryAttemptResult.Accepted
                is PassiveDelivery -> DeliveryAttemptResult.Deferred("passive")
                else -> DeliveryAttemptResult.Deferred("unknown")
            }
        }

        val outbox = DefaultMessageOutbox(
            decisionEngine = engine,
            sender = sender,
            clock = clock,
        )

        val msg = OutgoingMessage(
            messageId = "m1",
            chatId = "c1",
            payload = byteArrayOf(1, 2, 3),
            enqueueElapsedMs = clock.nowMs(),
        )

        assertEquals(EnqueueResult.Enqueued, outbox.enqueue(msg))
        assertEquals(1, outbox.snapshot.size)
        assertEquals(OutboxItem.Status.DEFERRED, outbox.snapshot.items.single().status)
        assertEquals(listOf("m1" to "PASSIVE"), sender.attempts)

        // Upgrade to active should retry.
        engine.emit(ActiveDelivery)
        assertEquals(2, sender.attempts.size)
        assertEquals(listOf("m1" to "PASSIVE", "m1" to "ACTIVE"), sender.attempts)
        assertEquals(OutboxItem.Status.IN_FLIGHT, outbox.snapshot.items.single().status)

        // Delivery confirmation removes the message.
        assertTrue(outbox.notifyDelivered("m1"))
        assertEquals(0, outbox.snapshot.size)

        outbox.close()
    }

    @Test
    fun no_duplication_on_strategy_change_while_in_flight() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(ActiveDelivery)

        val sender = RecordingSender { _, _ -> DeliveryAttemptResult.Accepted }

        val outbox = DefaultMessageOutbox(
            decisionEngine = engine,
            sender = sender,
            clock = clock,
        )

        val msg = OutgoingMessage("m2", "c1", byteArrayOf(9), clock.nowMs())
        outbox.enqueue(msg)

        assertEquals(OutboxItem.Status.IN_FLIGHT, outbox.snapshot.items.single().status)
        assertEquals(1, sender.attempts.size)

        // Switch strategy; outbox must not re-attempt because message is in-flight.
        engine.emit(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE))
        assertEquals(1, sender.attempts.size)

        outbox.close()
    }

    @Test
    fun debug_report_contains_expected_events_in_order() {
        val clock = FakeClock(0L)
        val engine = FakeDecisionEngine(PassiveDelivery(PassiveDelivery.QueueReason.UNKNOWN))

        val trace: MessageOutboxDebugTrace = DefaultMessageOutboxDebugTrace()

        val sender = RecordingSender { strategy, _ ->
            when (strategy) {
                is ActiveDelivery -> DeliveryAttemptResult.Accepted
                is PassiveDelivery -> DeliveryAttemptResult.Deferred("passive")
                else -> DeliveryAttemptResult.Deferred("unknown")
            }
        }

        val outbox = DefaultMessageOutbox(
            decisionEngine = engine,
            sender = sender,
            clock = clock,
            debugTrace = trace,
        )

        outbox.enqueue(OutgoingMessage("m3", "c1", byteArrayOf(0), clock.nowMs()))
        clock.advanceBy(10)
        engine.emit(ActiveDelivery)
        clock.advanceBy(10)
        outbox.notifyDelivered("m3")

        val report = trace.dumpDebugReport()

        val p1 = report.indexOf("ENQUEUE messageId=m3")
        val p2 = report.indexOf("ATTEMPT messageId=m3 mode=PASSIVE")
        val p3 = report.indexOf("RESULT messageId=m3 DEFERRED")
        val p4 = report.indexOf("STRATEGY_CHANGE PASSIVE -> ACTIVE")
        val p5 = report.indexOf("ATTEMPT messageId=m3 mode=ACTIVE")
        val p6 = report.indexOf("RESULT messageId=m3 ACCEPTED")
        val p7 = report.indexOf("DELIVERED messageId=m3")

        assertTrue(p1 >= 0)
        assertTrue(p2 >= 0)
        assertTrue(p3 >= 0)
        assertTrue(p4 >= 0)
        assertTrue(p5 >= 0)
        assertTrue(p6 >= 0)
        assertTrue(p7 >= 0)

        assertTrue(p1 < p2)
        assertTrue(p2 < p3)
        assertTrue(p3 < p4)
        assertTrue(p4 < p5)
        assertTrue(p5 < p6)
        assertTrue(p6 < p7)

        outbox.close()
    }
}
