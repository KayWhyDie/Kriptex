package com.ivor.kriptex.deliverypolicy.ledger

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.diagnostics.DefaultConversationDeliveryLedgerDebugTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
    fun advanceBy(deltaMs: Long) {
        now += deltaMs
    }
}

class ConversationDeliveryLedgerTest {
    @Test
    fun sent_is_not_acked_and_ack_is_monotonic() {
        val clock = FakeClock(0L)
        val trace = DefaultConversationDeliveryLedgerDebugTrace()
        val ledger = InMemoryConversationDeliveryLedger(clock = clock, debugTrace = trace)

        ledger.recordEnqueued("m1", "c1")
        ledger.recordSent("m1")

        assertEquals(MessageLifecycle.SENT, ledger.messageState("m1"))

        ledger.recordAcked("m1")
        assertEquals(MessageLifecycle.ACKED, ledger.messageState("m1"))

        // Monotonic: SENT after ACKED must not regress.
        ledger.recordSent("m1")
        assertEquals(MessageLifecycle.ACKED, ledger.messageState("m1"))

        val report = trace.dumpDebugReport()
        assertTrue(report.contains("TRANSITION messageId=m1 from=QUEUED to=SENT"))
        assertTrue(report.contains("TRANSITION messageId=m1 from=SENT to=ACKED"))
        assertTrue(report.contains("IDEMPOTENT event=SENT_IGNORED messageId=m1"))
    }

    @Test
    fun out_of_order_ack_does_not_advance_acked_prefix_cursor() {
        val clock = FakeClock(0L)
        val trace = DefaultConversationDeliveryLedgerDebugTrace()
        val ledger = InMemoryConversationDeliveryLedger(clock = clock, debugTrace = trace)

        ledger.recordEnqueued("m1", "c1")
        ledger.recordEnqueued("m2", "c1")
        ledger.recordEnqueued("m3", "c1")

        ledger.recordAcked("m3")

        val view = ledger.conversationView("c1")
        assertEquals(listOf("m1", "m2", "m3"), view.messages.map { it.messageId })
        assertEquals(MessageLifecycle.QUEUED, view.messages[0].state)
        assertEquals(MessageLifecycle.QUEUED, view.messages[1].state)
        assertEquals(MessageLifecycle.ACKED, view.messages[2].state)
        assertEquals(0, view.ackedPrefixCount)

        val report = trace.dumpDebugReport()
        assertTrue(report.contains("OUT_OF_ORDER_ACK conversationId=c1 index=2"))
    }

    @Test
    fun duplicate_ack_is_idempotent() {
        val clock = FakeClock(0L)
        val trace = DefaultConversationDeliveryLedgerDebugTrace()
        val ledger = InMemoryConversationDeliveryLedger(clock = clock, debugTrace = trace)

        ledger.recordEnqueued("m1", "c1")
        ledger.recordAcked("m1")
        ledger.recordAcked("m1")

        assertEquals(MessageLifecycle.ACKED, ledger.messageState("m1"))

        val report = trace.dumpDebugReport()
        assertTrue(report.contains("IDEMPOTENT event=ACK_DUPLICATE messageId=m1"))
    }

    @Test
    fun restore_does_not_regress_or_advance_state() {
        val clock = FakeClock(0L)
        val trace = DefaultConversationDeliveryLedgerDebugTrace()
        val ledger = InMemoryConversationDeliveryLedger(clock = clock, debugTrace = trace)

        ledger.recordEnqueued("m1", "c1")
        ledger.recordSent("m1")
        ledger.recordAcked("m1")

        val snap = ledger.snapshot()

        val clock2 = FakeClock(100L)
        val trace2 = DefaultConversationDeliveryLedgerDebugTrace()
        val ledger2 = InMemoryConversationDeliveryLedger(clock = clock2, debugTrace = trace2)
        ledger2.restore(snap)

        assertEquals(MessageLifecycle.ACKED, ledger2.messageState("m1"))

        // Must not auto-advance anything on restore.
        val view = ledger2.conversationView("c1")
        assertEquals(1, view.ackedPrefixCount)

        // No regression after restore.
        ledger2.recordSent("m1")
        assertEquals(MessageLifecycle.ACKED, ledger2.messageState("m1"))

        val report = trace2.dumpDebugReport()
        assertTrue(report.contains("RESTORE_APPLIED"))
    }
}
