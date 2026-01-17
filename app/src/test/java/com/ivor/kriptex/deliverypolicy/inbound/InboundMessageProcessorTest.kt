package com.ivor.kriptex.deliverypolicy.inbound

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.ledger.ConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.ledger.ConversationLedgerView
import com.ivor.kriptex.deliverypolicy.ledger.InMemoryConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.ledger.MessageLifecycle
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationDeliveryLedgerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
    fun advanceBy(deltaMs: Long) {
        now += deltaMs
    }
}

private class SpyLedger(
    private val delegate: ConversationDeliveryLedger,
) : ConversationDeliveryLedger {
    var enqueuedCalls = 0
        private set
    var sentCalls = 0
        private set
    var receivedCalls = 0
        private set
    var ackedCalls = 0
        private set
    var terminalCalls = 0
        private set

    override fun recordEnqueued(messageId: String, conversationId: String) {
        enqueuedCalls++
        delegate.recordEnqueued(messageId, conversationId)
    }

    override fun recordSent(messageId: String) {
        sentCalls++
        delegate.recordSent(messageId)
    }

    override fun recordReceived(messageId: String, conversationId: String) {
        receivedCalls++
        delegate.recordReceived(messageId, conversationId)
    }

    override fun recordAcked(messageId: String) {
        ackedCalls++
        delegate.recordAcked(messageId)
    }

    override fun recordTerminalFailure(messageId: String, reason: String?) {
        terminalCalls++
        delegate.recordTerminalFailure(messageId, reason)
    }

    override fun snapshot(): PersistedConversationDeliveryLedgerSnapshot = delegate.snapshot()

    override fun restore(snapshot: PersistedConversationDeliveryLedgerSnapshot) = delegate.restore(snapshot)

    override fun conversationView(conversationId: String): ConversationLedgerView = delegate.conversationView(conversationId)

    override fun messageState(messageId: String): MessageLifecycle? = delegate.messageState(messageId)
}

class InboundMessageProcessorTest {

    @Test
    fun data_message_is_deduped_ordered_updates_ledger_and_produces_pending_ack() {
        val clock = FakeClock(0L)
        val ledger = InMemoryConversationDeliveryLedger(clock = clock)
        val processor = InMemoryInboundMessageProcessor(ledger = ledger, clock = clock)

        val msg = InboundMessage(
            messageId = "m1",
            conversationId = "c1",
            senderId = "s1",
            payload = byteArrayOf(1, 2, 3),
            receivedAtElapsedMs = 10L,
            kind = InboundMessage.Kind.DATA,
        )

        val r1 = processor.onIncomingMessage(msg)
        assertTrue(r1 is InboundProcessingResult.Accepted)
        r1 as InboundProcessingResult.Accepted

        assertEquals(0, r1.receiveIndex)
        assertEquals(AckDecision.SEND_ACK, r1.ackDecision)
        assertTrue(r1.shouldDeliver)

        assertEquals(MessageLifecycle.RECEIVED, ledger.messageState("m1"))
        assertEquals(listOf("m1"), processor.conversationReceiveView("c1"))

        val pending = processor.drainPendingAcks()
        assertEquals(listOf(PendingAck(messageId = "m1", conversationId = "c1", receiveIndex = 0)), pending)
        assertTrue(processor.drainPendingAcks().isEmpty())

        // Duplicate.
        val r2 = processor.onIncomingMessage(msg)
        assertTrue(r2 is InboundProcessingResult.DuplicateIgnored)
        r2 as InboundProcessingResult.DuplicateIgnored
        assertEquals(0, r2.receiveIndex)
        assertEquals(AckDecision.NO_ACK, r2.ackDecision)

        // Duplicate must not re-queue ACK.
        assertTrue(processor.drainPendingAcks().isEmpty())
    }

    @Test
    fun ack_message_updates_ledger_but_is_not_delivered_and_does_not_generate_ack() {
        val clock = FakeClock(0L)
        val ledger = InMemoryConversationDeliveryLedger(clock = clock)
        val processor = InMemoryInboundMessageProcessor(ledger = ledger, clock = clock)

        // Outbound message exists and was sent.
        ledger.recordEnqueued("out1", "c1")
        ledger.recordSent("out1")

        val ack = InboundMessage(
            messageId = "out1",
            conversationId = "c1",
            senderId = "s2",
            payload = byteArrayOf(),
            receivedAtElapsedMs = 50L,
            kind = InboundMessage.Kind.ACK,
        )

        val r = processor.onIncomingMessage(ack)
        assertTrue(r is InboundProcessingResult.Accepted)
        r as InboundProcessingResult.Accepted

        assertEquals(AckDecision.NO_ACK, r.ackDecision)
        assertTrue(!r.shouldDeliver)

        assertEquals(MessageLifecycle.ACKED, ledger.messageState("out1"))
        assertTrue(processor.drainPendingAcks().isEmpty())
    }

    @Test
    fun snapshot_restore_never_auto_emits_acks_and_never_redelivers() {
        val clock = FakeClock(0L)
        val spy = SpyLedger(InMemoryConversationDeliveryLedger(clock = clock))
        val processor = InMemoryInboundMessageProcessor(ledger = spy, clock = clock)

        val msg = InboundMessage(
            messageId = "m1",
            conversationId = "c1",
            senderId = "s1",
            payload = byteArrayOf(9),
            receivedAtElapsedMs = 10L,
            kind = InboundMessage.Kind.DATA,
        )

        processor.onIncomingMessage(msg)

        val snap = processor.snapshot()
        assertEquals(1, spy.receivedCalls)

        val clock2 = FakeClock(100L)
        val spy2 = SpyLedger(InMemoryConversationDeliveryLedger(clock = clock2))
        val restored = InMemoryInboundMessageProcessor(ledger = spy2, clock = clock2)
        restored.restore(snap)

        // Restore must not touch ledger.
        assertEquals(0, spy2.receivedCalls)
        assertEquals(0, spy2.ackedCalls)

        // Pending ACK is still pending, but only emitted if drained.
        val pending = restored.drainPendingAcks()
        assertEquals(listOf(PendingAck("m1", "c1", 0)), pending)

        // Duplicate after restore must not re-deliver nor re-ACK.
        val dup = restored.onIncomingMessage(msg)
        assertTrue(dup is InboundProcessingResult.DuplicateIgnored)
        assertTrue(restored.drainPendingAcks().isEmpty())

        assertEquals(listOf("m1"), restored.conversationReceiveView("c1"))
    }

    @Test
    fun receive_index_is_per_conversation_and_respects_arrival_order() {
        val clock = FakeClock(0L)
        val ledger = InMemoryConversationDeliveryLedger(clock = clock)
        val processor = InMemoryInboundMessageProcessor(ledger = ledger, clock = clock)

        val m1 = InboundMessage("m1", "c1", "s", byteArrayOf(1), 1L, InboundMessage.Kind.DATA)
        val m2 = InboundMessage("m2", "c1", "s", byteArrayOf(1), 0L, InboundMessage.Kind.DATA) // older timestamp, later arrival
        val m3 = InboundMessage("m3", "c2", "s", byteArrayOf(1), 2L, InboundMessage.Kind.DATA)

        processor.onIncomingMessage(m1)
        processor.onIncomingMessage(m2)
        processor.onIncomingMessage(m3)

        assertEquals(listOf("m1", "m2"), processor.conversationReceiveView("c1"))
        assertEquals(listOf("m3"), processor.conversationReceiveView("c2"))
    }
}
