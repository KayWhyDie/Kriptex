package com.ivor.kriptex.deliverypolicy.messagestore

import com.ivor.kriptex.deliverypolicy.inbound.InMemoryInboundMessageProcessor
import com.ivor.kriptex.deliverypolicy.inbound.InboundMessage
import com.ivor.kriptex.deliverypolicy.ledger.InMemoryConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.messagestore.adapters.ConversationMessageStoreInboundAdapter
import com.ivor.kriptex.deliverypolicy.messagestore.adapters.ConversationMessageStoreLedgerAdapter
import com.ivor.kriptex.deliverypolicy.messagestore.adapters.ConversationMessageStoreOutboxAdapter
import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult
import com.ivor.kriptex.deliverypolicy.outbox.MessageOutbox
import com.ivor.kriptex.deliverypolicy.outbox.OutboxItem
import com.ivor.kriptex.deliverypolicy.outbox.OutboxSnapshot
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.persistence.PersistedMessageOutboxSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeOutbox : MessageOutbox {
    private val _flow = MutableStateFlow(OutboxSnapshot(size = 0, items = emptyList()))
    override val snapshotFlow: StateFlow<OutboxSnapshot> = _flow
    override val snapshot: OutboxSnapshot
        get() = _flow.value

    private val messages = LinkedHashMap<String, OutgoingMessage>()
    private val listeners = LinkedHashSet<(OutboxSnapshot) -> Unit>()

    override fun snapshot(): PersistedMessageOutboxSnapshot = PersistedMessageOutboxSnapshot(
        capturedAtElapsedMs = 0L,
        messages = emptyList(),
    )

    override fun restore(snapshot: PersistedMessageOutboxSnapshot) = Unit

    override fun enqueue(message: OutgoingMessage): EnqueueResult {
        if (messages.containsKey(message.messageId)) return EnqueueResult.AlreadyEnqueued
        messages[message.messageId] = message
        publish()
        return EnqueueResult.Enqueued
    }

    override fun notifyDelivered(messageId: String): Boolean {
        val removed = messages.remove(messageId) ?: return false
        publish()
        return removed.messageId == messageId
    }

    override fun notifyFailed(messageId: String, retryable: Boolean, reason: String?): Boolean = messages.containsKey(messageId)

    override fun addListener(listener: (OutboxSnapshot) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(snapshot)
        return { listeners.remove(listener) }
    }

    override fun close() {
        listeners.clear()
        messages.clear()
        publish()
    }

    private fun publish() {
        val items = messages.values.map {
            OutboxItem(
                messageId = it.messageId,
                chatId = it.chatId,
                status = OutboxItem.Status.QUEUED,
                enqueueElapsedMs = it.enqueueElapsedMs,
            )
        }
        val snap = OutboxSnapshot(size = items.size, items = items)
        _flow.value = snap
        listeners.forEach { it(snap) }
    }
}

class ConversationMessageStoreAdaptersTest {

    @Test
    fun outbox_enqueue_appends_outbound_to_store() {
        val store = InMemoryConversationMessageStore()
        val outbox = ConversationMessageStoreOutboxAdapter(FakeOutbox(), store)

        val msg = OutgoingMessage(
            messageId = "m1",
            chatId = "c1",
            payload = byteArrayOf(7),
            enqueueElapsedMs = 123L,
        )

        val res = outbox.enqueue(msg)
        assertEquals(EnqueueResult.Enqueued, res)

        val stored = store.message("m1")!!
        assertEquals(ConversationMessage.Direction.OUTBOUND, stored.direction)
        assertEquals(0, stored.sendIndex)
        assertEquals(ConversationMessage.State.QUEUED, stored.state)
    }

    @Test
    fun inbound_processor_accept_appends_inbound_and_ack_marks_outbound_acked() {
        val clock = TestClock(0L)
        val ledger = InMemoryConversationDeliveryLedger(clock = clock)
        val inbound = InMemoryInboundMessageProcessor(ledger = ledger, clock = clock)

        val store = InMemoryConversationMessageStore(clock = clock)
        val storeInbound = ConversationMessageStoreInboundAdapter(inbound, store)

        val data = InboundMessage(
            messageId = "i1",
            conversationId = "c1",
            senderId = "s",
            payload = byteArrayOf(1, 2),
            receivedAtElapsedMs = 10L,
            kind = InboundMessage.Kind.DATA,
        )

        val r1 = storeInbound.onIncomingMessage(data)
        assertTrue(r1 is com.ivor.kriptex.deliverypolicy.inbound.InboundProcessingResult.Accepted)
        assertEquals(ConversationMessage.Direction.INBOUND, store.message("i1")!!.direction)
        assertEquals(ConversationMessage.State.RECEIVED, store.message("i1")!!.state)

        // Prepare an outbound message and an inbound ACK for it.
        store.appendOutbound("o1", "c1", byteArrayOf(9), elapsedMs = 1L)
        ledger.recordEnqueued("o1", "c1")
        ledger.recordSent("o1")

        val ack = InboundMessage(
            messageId = "o1",
            conversationId = "c1",
            senderId = "s",
            payload = byteArrayOf(),
            receivedAtElapsedMs = 11L,
            kind = InboundMessage.Kind.ACK,
        )
        storeInbound.onIncomingMessage(ack)
        assertEquals(ConversationMessage.State.ACKED, store.message("o1")!!.state)
    }

    @Test
    fun ledger_transitions_drive_store_state_via_adapter() {
        val clock = TestClock(0L)
        val store = InMemoryConversationMessageStore(clock = clock)
        store.appendOutbound("m1", "c1", byteArrayOf(1), elapsedMs = 1L)

        val ledger = ConversationMessageStoreLedgerAdapter(
            delegate = InMemoryConversationDeliveryLedger(clock = clock),
            store = store,
            clock = clock,
        )

        ledger.recordEnqueued("m1", "c1")
        clock.set(10L)
        ledger.recordSent("m1")
        clock.set(11L)
        ledger.recordAcked("m1")

        assertEquals(ConversationMessage.State.ACKED, store.message("m1")!!.state)
    }
}
