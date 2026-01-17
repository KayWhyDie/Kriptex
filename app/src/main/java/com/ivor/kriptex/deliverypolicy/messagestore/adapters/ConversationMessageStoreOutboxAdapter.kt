package com.ivor.kriptex.deliverypolicy.messagestore.adapters

import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessageStore
import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult
import com.ivor.kriptex.deliverypolicy.outbox.MessageOutbox
import com.ivor.kriptex.deliverypolicy.outbox.OutboxSnapshot
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.persistence.PersistedMessageOutboxSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Explicit, injectable integration:
 * - delegates to an underlying [MessageOutbox]
 * - mirrors successful enqueues into [ConversationMessageStore.appendOutbound]
 */
class ConversationMessageStoreOutboxAdapter(
    private val delegate: MessageOutbox,
    private val store: ConversationMessageStore,
) : MessageOutbox {

    override val snapshotFlow: StateFlow<OutboxSnapshot>
        get() = delegate.snapshotFlow

    override val snapshot: OutboxSnapshot
        get() = delegate.snapshot

    override fun snapshot(): PersistedMessageOutboxSnapshot = delegate.snapshot()

    override fun restore(snapshot: PersistedMessageOutboxSnapshot) = delegate.restore(snapshot)

    override fun enqueue(message: OutgoingMessage): EnqueueResult {
        val result = delegate.enqueue(message)
        if (result == EnqueueResult.Enqueued) {
            store.appendOutbound(
                messageId = message.messageId,
                conversationId = message.chatId,
                payload = message.payload,
                elapsedMs = message.enqueueElapsedMs,
            )
        }
        return result
    }

    override fun notifyDelivered(messageId: String): Boolean = delegate.notifyDelivered(messageId)

    override fun notifyFailed(messageId: String, retryable: Boolean, reason: String?): Boolean =
        delegate.notifyFailed(messageId, retryable, reason)

    override fun addListener(listener: (OutboxSnapshot) -> Unit): () -> Unit = delegate.addListener(listener)

    override fun close() = delegate.close()
}
