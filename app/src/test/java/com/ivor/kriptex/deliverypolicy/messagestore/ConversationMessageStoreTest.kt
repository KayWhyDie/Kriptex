package com.ivor.kriptex.deliverypolicy.messagestore

import com.ivor.kriptex.deliverypolicy.diagnostics.DefaultConversationMessageStoreDebugTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMessageStoreTest {

    @Test
    fun inbound_outbound_interleaving_is_stable_and_indices_are_separate() {
        val clock = TestClock(0L)
        val trace = DefaultConversationMessageStoreDebugTrace()
        val store = InMemoryConversationMessageStore(clock = clock, debugTrace = trace)

        store.appendOutbound("o1", "c1", byteArrayOf(1), elapsedMs = 1L)
        store.appendInbound("i1", "c1", byteArrayOf(2), elapsedMs = 2L)
        store.appendOutbound("o2", "c1", byteArrayOf(3), elapsedMs = 3L)
        store.appendInbound("i2", "c1", byteArrayOf(4), elapsedMs = 4L)

        val timeline = store.conversationTimeline("c1")
        assertEquals(listOf("o1", "i1", "o2", "i2"), timeline.messages.map { it.messageId })

        val o1 = store.message("o1")!!
        val o2 = store.message("o2")!!
        val i1 = store.message("i1")!!
        val i2 = store.message("i2")!!

        assertEquals(0, o1.sendIndex)
        assertEquals(1, o2.sendIndex)
        assertEquals(null, o1.receiveIndex)

        assertEquals(0, i1.receiveIndex)
        assertEquals(1, i2.receiveIndex)
        assertEquals(null, i1.sendIndex)

        val report = trace.dumpDebugReport()
        assertTrue(report.contains("APPEND conversationId=c1 direction=OUTBOUND"))
        assertTrue(report.contains("APPEND conversationId=c1 direction=INBOUND"))
    }

    @Test
    fun duplicate_inbound_is_ignored() {
        val clock = TestClock(0L)
        val store = InMemoryConversationMessageStore(clock = clock)

        val r1 = store.appendInbound("m1", "c1", byteArrayOf(9), elapsedMs = 10L)
        val r2 = store.appendInbound("m1", "c1", byteArrayOf(9), elapsedMs = 11L)

        assertEquals(AppendResult.Appended, r1)
        assertEquals(AppendResult.DuplicateIgnored, r2)

        val timeline = store.conversationTimeline("c1")
        assertEquals(listOf("m1"), timeline.messages.map { it.messageId })
    }

    @Test
    fun state_transitions_are_monotonic_and_do_not_reorder_timeline() {
        val clock = TestClock(0L)
        val store = InMemoryConversationMessageStore(clock = clock)

        store.appendOutbound("o1", "c1", byteArrayOf(1), elapsedMs = 1L)
        store.appendOutbound("o2", "c1", byteArrayOf(2), elapsedMs = 2L)

        store.markSent("o1", elapsedMs = 3L)
        store.markAcked("o1", elapsedMs = 4L)
        store.markSent("o1", elapsedMs = 5L) // must not regress

        assertEquals(ConversationMessage.State.ACKED, store.message("o1")!!.state)

        val timeline = store.conversationTimeline("c1")
        assertEquals(listOf("o1", "o2"), timeline.messages.map { it.messageId })
    }

    @Test
    fun snapshot_restore_preserves_timeline_and_next_indices() {
        val clock = TestClock(0L)
        val store = InMemoryConversationMessageStore(clock = clock)

        store.appendOutbound("o1", "c1", byteArrayOf(1), elapsedMs = 1L)
        store.appendInbound("i1", "c1", byteArrayOf(2), elapsedMs = 2L)
        store.markSent("o1", elapsedMs = 3L)

        val snap = store.snapshot()

        val clock2 = TestClock(100L)
        val store2 = InMemoryConversationMessageStore(clock = clock2)
        store2.restore(snap)

        val timeline2 = store2.conversationTimeline("c1")
        assertEquals(listOf("o1", "i1"), timeline2.messages.map { it.messageId })
        assertEquals(ConversationMessage.State.SENT, store2.message("o1")!!.state)

        // Next indices should continue without reordering.
        store2.appendOutbound("o2", "c1", byteArrayOf(3), elapsedMs = 110L)
        store2.appendInbound("i2", "c1", byteArrayOf(4), elapsedMs = 111L)

        assertEquals(1, store2.message("o2")!!.sendIndex)
        assertEquals(1, store2.message("i2")!!.receiveIndex)

        val timeline3 = store2.conversationTimeline("c1")
        assertEquals(listOf("o1", "i1", "o2", "i2"), timeline3.messages.map { it.messageId })
    }
}
