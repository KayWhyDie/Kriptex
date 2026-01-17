package com.ivor.kriptex.deliverypolicy.ledger

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.diagnostics.ConversationDeliveryLedgerDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpConversationDeliveryLedgerDebugTrace
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationDeliveryLedgerSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedLedgerEntry
import com.ivor.kriptex.deliverypolicy.persistence.PersistedLedgerState

/**
 * Conversation-level delivery ledger.
 *
 * Separation of concerns:
 * - Outbox handles attempts
 * - Ledger handles logical truth
 * - Active/Passive delivery informs SENT, never ACKED
 */
interface ConversationDeliveryLedger {
    fun recordEnqueued(messageId: String, conversationId: String)

    fun recordSent(messageId: String)

    /**
     * Records that this device has received a message for a conversation.
     * This is intended for inbound messages (not ACKs).
     */
    fun recordReceived(messageId: String, conversationId: String)

    fun recordAcked(messageId: String)

    fun recordTerminalFailure(messageId: String, reason: String? = null)

    fun snapshot(): PersistedConversationDeliveryLedgerSnapshot

    fun restore(snapshot: PersistedConversationDeliveryLedgerSnapshot)

    /** For tests/debugging: returns a read-only view of a conversation timeline. */
    fun conversationView(conversationId: String): ConversationLedgerView

    /** For tests/debugging: returns current lifecycle for a message, if known. */
    fun messageState(messageId: String): MessageLifecycle?
}

data class ConversationLedgerView(
    val conversationId: String,
    val messages: List<MessageView>,
    /** Number of contiguous messages from the start that are ACKED. */
    val ackedPrefixCount: Int,
)

data class MessageView(
    val messageId: String,
    val index: Int,
    val state: MessageLifecycle,
)

sealed interface MessageLifecycle {
    data object QUEUED : MessageLifecycle
    data object SENT : MessageLifecycle
    data object RECEIVED : MessageLifecycle
    data object ACKED : MessageLifecycle
    data class FAILED_TERMINAL(val reason: String? = null) : MessageLifecycle
}

class InMemoryConversationDeliveryLedger(
    private val clock: Clock = MonotonicClock,
    private val debugTrace: ConversationDeliveryLedgerDebugTrace = NoOpConversationDeliveryLedgerDebugTrace,
) : ConversationDeliveryLedger {

    private data class Entry(
        val messageId: String,
        val conversationId: String,
        val index: Int,
        var state: MessageLifecycle,
    )

    private val entriesByMessageId = LinkedHashMap<String, Entry>()
    private val conversationOrder = LinkedHashMap<String, LinkedHashMap<String, Int>>()
    private val nextIndexByConversation = LinkedHashMap<String, Int>()

    // If events arrive before enqueue, we stage them until we know the conversation.
    private val pendingStateByMessageId = HashMap<String, MessageLifecycle>()

    @Synchronized
    override fun recordEnqueued(messageId: String, conversationId: String) {
        val existing = entriesByMessageId[messageId]
        if (existing != null) {
            debugTrace.onIdempotentEvent(messageId, event = "ENQUEUE", elapsedMs = clock.nowMs())
            return
        }

        val index = nextIndexByConversation.getOrDefault(conversationId, 0)
        nextIndexByConversation[conversationId] = index + 1

        val order = conversationOrder.getOrPut(conversationId) { LinkedHashMap() }
        order[messageId] = index

        val initial = pendingStateByMessageId.remove(messageId) ?: MessageLifecycle.QUEUED
        val entry = Entry(messageId, conversationId, index, initial)
        entriesByMessageId[messageId] = entry

        debugTrace.onEnqueued(messageId, conversationId, index, clock.nowMs())

        if (initial != MessageLifecycle.QUEUED) {
            debugTrace.onTransition(messageId, from = "QUEUED", to = initial.label(), elapsedMs = clock.nowMs())
        }
    }

    @Synchronized
    override fun recordSent(messageId: String) {
        val entry = entriesByMessageId[messageId]
        if (entry == null) {
            // Stage until enqueue.
            pendingStateByMessageId[messageId] = merge(pendingStateByMessageId[messageId], MessageLifecycle.SENT)
            debugTrace.onIdempotentEvent(messageId, event = "SENT_STAGED", elapsedMs = clock.nowMs())
            return
        }

        val next = when (entry.state) {
            MessageLifecycle.QUEUED -> MessageLifecycle.SENT
            MessageLifecycle.SENT,
            MessageLifecycle.RECEIVED,
            MessageLifecycle.ACKED,
            is MessageLifecycle.FAILED_TERMINAL,
            -> {
                debugTrace.onIdempotentEvent(messageId, event = "SENT_IGNORED", elapsedMs = clock.nowMs())
                return
            }
        }

        transition(entry, next)
    }

    @Synchronized
    override fun recordReceived(messageId: String, conversationId: String) {
        val existing = entriesByMessageId[messageId]
        if (existing != null) {
            when (existing.state) {
                MessageLifecycle.RECEIVED -> {
                    debugTrace.onIdempotentEvent(messageId, event = "RECEIVED_DUPLICATE", elapsedMs = clock.nowMs())
                    return
                }

                MessageLifecycle.QUEUED,
                MessageLifecycle.SENT,
                MessageLifecycle.ACKED,
                is MessageLifecycle.FAILED_TERMINAL,
                -> {
                    debugTrace.onIdempotentEvent(messageId, event = "RECEIVED_IGNORED", elapsedMs = clock.nowMs())
                    return
                }
            }
        }

        // We know the conversationId for inbound messages, so we can enqueue immediately.
        pendingStateByMessageId[messageId] = merge(pendingStateByMessageId[messageId], MessageLifecycle.RECEIVED)
        recordEnqueued(messageId, conversationId)
    }

    @Synchronized
    override fun recordAcked(messageId: String) {
        val entry = entriesByMessageId[messageId]
        if (entry == null) {
            // Stage until enqueue.
            pendingStateByMessageId[messageId] = merge(pendingStateByMessageId[messageId], MessageLifecycle.ACKED)
            debugTrace.onIdempotentEvent(messageId, event = "ACK_STAGED", elapsedMs = clock.nowMs())
            return
        }

        when (entry.state) {
            MessageLifecycle.ACKED -> {
                debugTrace.onIdempotentEvent(messageId, event = "ACK_DUPLICATE", elapsedMs = clock.nowMs())
                return
            }

            MessageLifecycle.RECEIVED -> {
                debugTrace.onIdempotentEvent(messageId, event = "ACK_IGNORED_RECEIVED", elapsedMs = clock.nowMs())
                return
            }

            is MessageLifecycle.FAILED_TERMINAL -> {
                debugTrace.onIdempotentEvent(messageId, event = "ACK_IGNORED_TERMINAL", elapsedMs = clock.nowMs())
                return
            }

            MessageLifecycle.QUEUED,
            MessageLifecycle.SENT,
            -> {
                transition(entry, MessageLifecycle.ACKED)
                val view = conversationView(entry.conversationId)
                // If this ACK didn't advance the cursor to include this index, it's out-of-order.
                if (view.ackedPrefixCount <= entry.index) {
                    debugTrace.onOutOfOrderAck(
                        messageId = messageId,
                        conversationId = entry.conversationId,
                        index = entry.index,
                        ackedPrefixCount = view.ackedPrefixCount,
                        elapsedMs = clock.nowMs(),
                    )
                }
            }
        }
    }

    @Synchronized
    override fun recordTerminalFailure(messageId: String, reason: String?) {
        val entry = entriesByMessageId[messageId]
        if (entry == null) {
            pendingStateByMessageId[messageId] = merge(
                pendingStateByMessageId[messageId],
                MessageLifecycle.FAILED_TERMINAL(reason),
            )
            debugTrace.onIdempotentEvent(messageId, event = "TERMINAL_STAGED", elapsedMs = clock.nowMs())
            return
        }

        when (entry.state) {
            MessageLifecycle.ACKED -> {
                debugTrace.onIdempotentEvent(messageId, event = "TERMINAL_IGNORED_ACKED", elapsedMs = clock.nowMs())
                return
            }

            MessageLifecycle.RECEIVED -> {
                debugTrace.onIdempotentEvent(messageId, event = "TERMINAL_IGNORED_RECEIVED", elapsedMs = clock.nowMs())
                return
            }

            is MessageLifecycle.FAILED_TERMINAL -> {
                debugTrace.onIdempotentEvent(messageId, event = "TERMINAL_DUPLICATE", elapsedMs = clock.nowMs())
                return
            }

            MessageLifecycle.QUEUED,
            MessageLifecycle.SENT,
            -> transition(entry, MessageLifecycle.FAILED_TERMINAL(reason))
        }
    }

    @Synchronized
    override fun snapshot(): PersistedConversationDeliveryLedgerSnapshot {
        val capturedAt = clock.nowMs()
        val persisted = entriesByMessageId.values.map {
            PersistedLedgerEntry(
                messageId = it.messageId,
                conversationId = it.conversationId,
                index = it.index,
                state = it.state.toPersisted(),
                terminalFailureReason = (it.state as? MessageLifecycle.FAILED_TERMINAL)?.reason,
            )
        }
        debugTrace.onSnapshotBuilt(
            messageCount = persisted.size,
            conversationCount = conversationOrder.size,
            elapsedMs = capturedAt,
        )
        return PersistedConversationDeliveryLedgerSnapshot(
            capturedAtElapsedMs = capturedAt,
            entries = persisted,
        )
    }

    @Synchronized
    override fun restore(snapshot: PersistedConversationDeliveryLedgerSnapshot) {
        entriesByMessageId.clear()
        conversationOrder.clear()
        nextIndexByConversation.clear()
        pendingStateByMessageId.clear()

        snapshot.entries.forEach { e ->
            val state = e.toRuntimeState()
            val entry = Entry(e.messageId, e.conversationId, e.index, state)
            entriesByMessageId[e.messageId] = entry
            val order = conversationOrder.getOrPut(e.conversationId) { LinkedHashMap() }
            order[e.messageId] = e.index

            val next = nextIndexByConversation.getOrDefault(e.conversationId, 0)
            if (e.index + 1 > next) nextIndexByConversation[e.conversationId] = e.index + 1
        }

        val now = clock.nowMs()
        debugTrace.onRestoreApplied(
            messageCount = entriesByMessageId.size,
            conversationCount = conversationOrder.size,
            elapsedMs = now,
        )
        // Restore never triggers delivery attempts.
    }

    @Synchronized
    override fun conversationView(conversationId: String): ConversationLedgerView {
        val order = conversationOrder[conversationId] ?: LinkedHashMap()
        // Sort by index for safety even though insertion order matches index.
        val ordered = order.entries.sortedBy { it.value }
        val messages = ordered.mapNotNull { (messageId, index) ->
            val entry = entriesByMessageId[messageId] ?: return@mapNotNull null
            MessageView(messageId = messageId, index = index, state = entry.state)
        }

        val ackedPrefixCount = computeAckedPrefixCount(messages)
        return ConversationLedgerView(conversationId = conversationId, messages = messages, ackedPrefixCount = ackedPrefixCount)
    }

    @Synchronized
    override fun messageState(messageId: String): MessageLifecycle? = entriesByMessageId[messageId]?.state

    private fun computeAckedPrefixCount(messages: List<MessageView>): Int {
        var count = 0
        for (m in messages) {
            if (m.state == MessageLifecycle.ACKED) count++ else break
        }
        return count
    }

    private fun transition(entry: Entry, next: MessageLifecycle) {
        val prev = entry.state
        if (prev == next) {
            debugTrace.onIdempotentEvent(entry.messageId, event = "TRANSITION_NOOP", elapsedMs = clock.nowMs())
            return
        }
        // Prevent regression.
        if (next.rank() < prev.rank()) {
            debugTrace.onIdempotentEvent(entry.messageId, event = "TRANSITION_REGRESSION_IGNORED", elapsedMs = clock.nowMs())
            return
        }
        entry.state = next
        debugTrace.onTransition(entry.messageId, from = prev.label(), to = next.label(), elapsedMs = clock.nowMs())
    }

    private fun merge(current: MessageLifecycle?, next: MessageLifecycle): MessageLifecycle {
        if (current == null) return next
        return if (next.rank() >= current.rank()) next else current
    }
}

private fun MessageLifecycle.rank(): Int = when (this) {
    MessageLifecycle.QUEUED -> 0
    MessageLifecycle.SENT -> 1
    MessageLifecycle.RECEIVED -> 1
    MessageLifecycle.ACKED -> 2
    is MessageLifecycle.FAILED_TERMINAL -> 3
}

private fun MessageLifecycle.label(): String = when (this) {
    MessageLifecycle.QUEUED -> "QUEUED"
    MessageLifecycle.SENT -> "SENT"
    MessageLifecycle.RECEIVED -> "RECEIVED"
    MessageLifecycle.ACKED -> "ACKED"
    is MessageLifecycle.FAILED_TERMINAL -> "FAILED_TERMINAL"
}

private fun MessageLifecycle.toPersisted(): PersistedLedgerState = when (this) {
    MessageLifecycle.QUEUED -> PersistedLedgerState.QUEUED
    MessageLifecycle.SENT -> PersistedLedgerState.SENT
    MessageLifecycle.RECEIVED -> PersistedLedgerState.RECEIVED
    MessageLifecycle.ACKED -> PersistedLedgerState.ACKED
    is MessageLifecycle.FAILED_TERMINAL -> PersistedLedgerState.FAILED_TERMINAL
}

private fun PersistedLedgerEntry.toRuntimeState(): MessageLifecycle = when (state) {
    PersistedLedgerState.QUEUED -> MessageLifecycle.QUEUED
    PersistedLedgerState.SENT -> MessageLifecycle.SENT
    PersistedLedgerState.RECEIVED -> MessageLifecycle.RECEIVED
    PersistedLedgerState.ACKED -> MessageLifecycle.ACKED
    PersistedLedgerState.FAILED_TERMINAL -> MessageLifecycle.FAILED_TERMINAL(terminalFailureReason)
}
