package com.ivor.kriptex.deliverypolicy.messagestore.adapters

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.ledger.ConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.ledger.ConversationLedgerView
import com.ivor.kriptex.deliverypolicy.ledger.MessageLifecycle
import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessageStore

/**
 * Explicit, injectable integration:
 * - delegates to an underlying [ConversationDeliveryLedger]
 * - mirrors ledger events into [ConversationMessageStore] state
 */
class ConversationMessageStoreLedgerAdapter(
    private val delegate: ConversationDeliveryLedger,
    private val store: ConversationMessageStore,
    private val clock: Clock = MonotonicClock,
) : ConversationDeliveryLedger {

    override fun recordEnqueued(messageId: String, conversationId: String) {
        delegate.recordEnqueued(messageId, conversationId)
        // If the outbound append hook wasn't used, create a placeholder to keep the store consistent.
        if (store.message(messageId) == null) {
            store.appendOutbound(messageId, conversationId, payload = byteArrayOf(), elapsedMs = clock.nowMs())
        }
    }

    override fun recordSent(messageId: String) {
        delegate.recordSent(messageId)
        store.markSent(messageId, elapsedMs = clock.nowMs())
    }

    override fun recordReceived(messageId: String, conversationId: String) {
        delegate.recordReceived(messageId, conversationId)
        // If the inbound append hook wasn't used, create a placeholder.
        if (store.message(messageId) == null) {
            store.appendInbound(messageId, conversationId, payload = byteArrayOf(), elapsedMs = clock.nowMs())
        } else {
            store.markReceived(messageId, elapsedMs = clock.nowMs())
        }
    }

    override fun recordAcked(messageId: String) {
        delegate.recordAcked(messageId)
        store.markAcked(messageId, elapsedMs = clock.nowMs())
    }

    override fun recordTerminalFailure(messageId: String, reason: String?) {
        delegate.recordTerminalFailure(messageId, reason)
        store.markFailed(messageId, elapsedMs = clock.nowMs(), reason = reason)
    }

    override fun snapshot() = delegate.snapshot()

    override fun restore(snapshot: com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationDeliveryLedgerSnapshot) =
        delegate.restore(snapshot)

    override fun conversationView(conversationId: String): ConversationLedgerView = delegate.conversationView(conversationId)

    override fun messageState(messageId: String): MessageLifecycle? = delegate.messageState(messageId)
}
