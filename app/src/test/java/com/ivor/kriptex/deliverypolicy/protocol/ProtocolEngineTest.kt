package com.ivor.kriptex.deliverypolicy.protocol

import com.ivor.kriptex.deliverypolicy.ledger.InMemoryConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.messagestore.InMemoryConversationMessageStore
import com.ivor.kriptex.deliverypolicy.messagestore.adapters.ConversationMessageStoreOutboxAdapter
import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult
import com.ivor.kriptex.deliverypolicy.outbox.MessageOutbox
import com.ivor.kriptex.deliverypolicy.outbox.OutboxItem
import com.ivor.kriptex.deliverypolicy.outbox.OutboxSnapshot
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.persistence.PersistedMessageOutboxSnapshot
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolInboundResult.Accepted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingOutbox : MessageOutbox {
    private val _flow = MutableStateFlow(OutboxSnapshot(size = 0, items = emptyList()))
    override val snapshotFlow: StateFlow<OutboxSnapshot> = _flow
    override val snapshot: OutboxSnapshot
        get() = _flow.value

    val enqueued = ArrayList<OutgoingMessage>()

    override fun snapshot(): PersistedMessageOutboxSnapshot = PersistedMessageOutboxSnapshot(
        capturedAtElapsedMs = 0L,
        messages = emptyList(),
    )

    override fun restore(snapshot: PersistedMessageOutboxSnapshot) = Unit

    override fun enqueue(message: OutgoingMessage): EnqueueResult {
        enqueued.add(message)
        _flow.value = OutboxSnapshot(
            size = enqueued.size,
            items = enqueued.map {
                OutboxItem(
                    messageId = it.messageId,
                    chatId = it.chatId,
                    status = OutboxItem.Status.QUEUED,
                    enqueueElapsedMs = it.enqueueElapsedMs,
                )
            },
        )
        return EnqueueResult.Enqueued
    }

    override fun notifyDelivered(messageId: String): Boolean = false

    override fun notifyFailed(messageId: String, retryable: Boolean, reason: String?): Boolean = false

    override fun addListener(listener: (OutboxSnapshot) -> Unit): () -> Unit {
        listener(snapshot)
        return {}
    }

    override fun close() = Unit
}

class ProtocolEngineTest {

    @Test
    fun inbound_user_message_generates_outbound_ack_enqueued_via_outbox_and_ordered_in_store() {
        val codec = BinaryProtocolCodec()
        val generator = IncrementingMessageIdGenerator(prefix = "ack", start = 1)

        val ledger = InMemoryConversationDeliveryLedger()
        val store = InMemoryConversationMessageStore()

        val recordingOutbox = RecordingOutbox()
        val outbox = ConversationMessageStoreOutboxAdapter(recordingOutbox, store)

        val inbound = InMemoryProtocolInboundPipeline(
            decoder = codec,
            encoder = codec,
            messageIdGenerator = generator,
            ledger = ledger,
            messageStore = store,
        )
        val outbound = ProtocolOutboundSender(outbox = outbox, encoder = codec)
        val engine = ProtocolEngine(inbound = inbound, outbound = outbound)

        val user = UserMessage("u1", "c1", createdAtElapsedMs = 10L, payload = byteArrayOf(9))
        val bytes = codec.encode(user)

        val result = engine.onInboundBytes(bytes, receivedAtElapsedMs = 11L, senderId = "s", autoEnqueuePendingOutbound = true)
        assertTrue(result.inbound is Accepted)

        // One ACK should have been enqueued.
        assertEquals(1, recordingOutbox.enqueued.size)
        val ackOut = codec.decode(recordingOutbox.enqueued[0].payload) as AckMessage
        assertEquals("u1", ackOut.ackedMessageId)
        assertEquals("c1", ackOut.conversationId)

        // Store timeline should keep inbound user first, then outbound ack.
        val timeline = store.conversationTimeline("c1")
        assertEquals(listOf("u1", ackOut.messageId), timeline.messages.map { it.messageId })
    }

    @Test
    fun inbound_ack_updates_ledger_and_store_for_acked_message_id_and_dedup_works() {
        val codec = BinaryProtocolCodec()
        val generator = IncrementingMessageIdGenerator(prefix = "ack", start = 1)

        val ledger = InMemoryConversationDeliveryLedger()
        val store = InMemoryConversationMessageStore()

        // Ensure acked message exists (outbound) so updates can be observed.
        store.appendOutbound("o1", "c1", payload = byteArrayOf(1), elapsedMs = 1L)
        ledger.recordEnqueued("o1", "c1")

        val inbound = InMemoryProtocolInboundPipeline(
            decoder = codec,
            encoder = codec,
            messageIdGenerator = generator,
            ledger = ledger,
            messageStore = store,
        )

        val ack = AckMessage(messageId = "a1", conversationId = "c1", createdAtElapsedMs = 5L, ackedMessageId = "o1")
        val bytes = codec.encode(ack)

        val r1 = inbound.onInboundBytes(bytes, receivedAtElapsedMs = 6L, senderId = "s")
        assertTrue(r1 is Accepted)
        assertEquals(com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessage.State.ACKED, store.message("o1")!!.state)
        assertEquals(com.ivor.kriptex.deliverypolicy.ledger.MessageLifecycle.ACKED, ledger.messageState("o1"))

        val r2 = inbound.onInboundBytes(bytes, receivedAtElapsedMs = 7L, senderId = "s")
        assertTrue(r2 is ProtocolInboundResult.DuplicateIgnored)
        assertEquals(com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessage.State.ACKED, store.message("o1")!!.state)
    }

    @Test
    fun restore_is_safe_pending_outbound_is_not_auto_enqueued_but_can_be_flushed() {
        val codec = BinaryProtocolCodec()
        val generator1 = IncrementingMessageIdGenerator(prefix = "ack", start = 1)

        val ledger1 = InMemoryConversationDeliveryLedger()
        val store1 = InMemoryConversationMessageStore()
        val recordingOutbox1 = RecordingOutbox()
        val outbox1 = ConversationMessageStoreOutboxAdapter(recordingOutbox1, store1)

        val inbound1 = InMemoryProtocolInboundPipeline(
            decoder = codec,
            encoder = codec,
            messageIdGenerator = generator1,
            ledger = ledger1,
            messageStore = store1,
        )

        val user = UserMessage("u1", "c1", createdAtElapsedMs = 10L, payload = byteArrayOf(9))
        inbound1.onInboundBytes(codec.encode(user), receivedAtElapsedMs = 11L, senderId = "s")

        val snap = inbound1.snapshot()

        val ledger2 = InMemoryConversationDeliveryLedger()
        val store2 = InMemoryConversationMessageStore()
        val recordingOutbox2 = RecordingOutbox()
        val outbox2 = ConversationMessageStoreOutboxAdapter(recordingOutbox2, store2)

        val inbound2 = InMemoryProtocolInboundPipeline(
            decoder = codec,
            encoder = codec,
            messageIdGenerator = IncrementingMessageIdGenerator(prefix = "ack", start = 1),
            ledger = ledger2,
            messageStore = store2,
        )
        inbound2.restore(snap)

        // Restore must not enqueue anything.
        assertEquals(0, recordingOutbox2.enqueued.size)

        val outbound2 = ProtocolOutboundSender(outbox = outbox2, encoder = codec)
        val engine2 = ProtocolEngine(inbound = inbound2, outbound = outbound2)

        val flushed = engine2.flushPendingOutbound()
        assertEquals(1, flushed.size)
        assertEquals(1, recordingOutbox2.enqueued.size)
    }
}
