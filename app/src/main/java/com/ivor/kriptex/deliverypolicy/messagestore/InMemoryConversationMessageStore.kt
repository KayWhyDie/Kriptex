package com.ivor.kriptex.deliverypolicy.messagestore

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.diagnostics.ConversationMessageStoreDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpConversationMessageStoreDebugTrace
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationMessage
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationMessageStoreSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationTimeline

class InMemoryConversationMessageStore(
    private val clock: Clock = MonotonicClock,
    private val debugTrace: ConversationMessageStoreDebugTrace = NoOpConversationMessageStoreDebugTrace,
) : ConversationMessageStore {

    private data class Entry(
        var message: ConversationMessage,
        var failureReason: String? = null,
    )

    private val entriesByMessageId = LinkedHashMap<String, Entry>()

    // Stable mixed timeline ordering per conversation (messageIds in append order).
    private val timelineByConversation = LinkedHashMap<String, ArrayList<String>>()

    private val nextSendIndexByConversation = LinkedHashMap<String, Int>()
    private val nextReceiveIndexByConversation = LinkedHashMap<String, Int>()

    private var closed = false

    @Synchronized
    override fun appendOutbound(messageId: String, conversationId: String, payload: ByteArray, elapsedMs: Long): AppendResult {
        if (closed) return AppendResult.DuplicateIgnored

        val existing = entriesByMessageId[messageId]
        if (existing != null) {
            debugTrace.onDuplicateIgnored(messageId, conversationId, elapsedMs)
            return AppendResult.DuplicateIgnored
        }

        val sendIndex = nextSendIndexByConversation.getOrDefault(conversationId, 0)
        nextSendIndexByConversation[conversationId] = sendIndex + 1

        val ts = ConversationMessage.Timestamps(
            createdAtElapsedMs = elapsedMs,
            updatedAtElapsedMs = elapsedMs,
            queuedAtElapsedMs = elapsedMs,
        )
        val msg = ConversationMessage(
            messageId = messageId,
            conversationId = conversationId,
            direction = ConversationMessage.Direction.OUTBOUND,
            payload = payload,
            sendIndex = sendIndex,
            receiveIndex = null,
            state = ConversationMessage.State.QUEUED,
            timestamps = ts,
        )

        entriesByMessageId[messageId] = Entry(message = msg)
        timelineByConversation.getOrPut(conversationId) { ArrayList() }.add(messageId)

        debugTrace.onAppended(
            messageId = messageId,
            conversationId = conversationId,
            direction = msg.direction,
            sendIndex = sendIndex,
            receiveIndex = null,
            state = msg.state,
            elapsedMs = elapsedMs,
        )

        verifyOrderingForConversation(conversationId, elapsedMs)
        return AppendResult.Appended
    }

    @Synchronized
    override fun appendInbound(messageId: String, conversationId: String, payload: ByteArray, elapsedMs: Long): AppendResult {
        if (closed) return AppendResult.DuplicateIgnored

        val existing = entriesByMessageId[messageId]
        if (existing != null) {
            debugTrace.onDuplicateIgnored(messageId, conversationId, elapsedMs)
            return AppendResult.DuplicateIgnored
        }

        val receiveIndex = nextReceiveIndexByConversation.getOrDefault(conversationId, 0)
        nextReceiveIndexByConversation[conversationId] = receiveIndex + 1

        val ts = ConversationMessage.Timestamps(
            createdAtElapsedMs = elapsedMs,
            updatedAtElapsedMs = elapsedMs,
            receivedAtElapsedMs = elapsedMs,
        )
        val msg = ConversationMessage(
            messageId = messageId,
            conversationId = conversationId,
            direction = ConversationMessage.Direction.INBOUND,
            payload = payload,
            sendIndex = null,
            receiveIndex = receiveIndex,
            state = ConversationMessage.State.RECEIVED,
            timestamps = ts,
        )

        entriesByMessageId[messageId] = Entry(message = msg)
        timelineByConversation.getOrPut(conversationId) { ArrayList() }.add(messageId)

        debugTrace.onAppended(
            messageId = messageId,
            conversationId = conversationId,
            direction = msg.direction,
            sendIndex = null,
            receiveIndex = receiveIndex,
            state = msg.state,
            elapsedMs = elapsedMs,
        )

        verifyOrderingForConversation(conversationId, elapsedMs)
        return AppendResult.Appended
    }

    @Synchronized
    override fun markSent(messageId: String, elapsedMs: Long): Boolean {
        return transition(messageId, ConversationMessage.State.SENT, elapsedMs)
    }

    @Synchronized
    override fun markReceived(messageId: String, elapsedMs: Long): Boolean {
        return transition(messageId, ConversationMessage.State.RECEIVED, elapsedMs)
    }

    @Synchronized
    override fun markAcked(messageId: String, elapsedMs: Long): Boolean {
        return transition(messageId, ConversationMessage.State.ACKED, elapsedMs)
    }

    @Synchronized
    override fun markFailed(messageId: String, elapsedMs: Long, reason: String?): Boolean {
        val ok = transition(messageId, ConversationMessage.State.FAILED_TERMINAL, elapsedMs)
        if (ok) {
            val entry = entriesByMessageId[messageId]
            if (entry != null) entry.failureReason = reason
        }
        return ok
    }

    @Synchronized
    override fun conversationTimeline(conversationId: String): ConversationTimeline {
        val ids = timelineByConversation[conversationId].orEmpty()
        val msgs = ids.mapNotNull { entriesByMessageId[it]?.message }
        return ConversationTimeline(conversationId = conversationId, messages = msgs)
    }

    @Synchronized
    override fun message(messageId: String): ConversationMessage? = entriesByMessageId[messageId]?.message

    @Synchronized
    override fun snapshot(): PersistedConversationMessageStoreSnapshot {
        val capturedAt = clock.nowMs()

        val conversations = timelineByConversation.mapValues { (conversationId, ids) ->
            PersistedConversationTimeline(conversationId = conversationId, orderedMessageIds = ids.toList())
        }

        val messages = entriesByMessageId.mapValues { (_, entry) ->
            val m = entry.message
            PersistedConversationMessage(
                messageId = m.messageId,
                conversationId = m.conversationId,
                direction = m.direction,
                payload = m.payload,
                sendIndex = m.sendIndex,
                receiveIndex = m.receiveIndex,
                state = m.state,
                timestamps = m.timestamps,
                failureReason = entry.failureReason,
            )
        }

        debugTrace.onSnapshotBuilt(
            conversationCount = conversations.size,
            messageCount = messages.size,
            elapsedMs = capturedAt,
        )

        return PersistedConversationMessageStoreSnapshot(
            capturedAtElapsedMs = capturedAt,
            conversations = conversations,
            messages = messages,
            nextSendIndexByConversation = nextSendIndexByConversation.toMap(),
            nextReceiveIndexByConversation = nextReceiveIndexByConversation.toMap(),
        )
    }

    @Synchronized
    override fun restore(snapshot: PersistedConversationMessageStoreSnapshot) {
        entriesByMessageId.clear()
        timelineByConversation.clear()
        nextSendIndexByConversation.clear()
        nextReceiveIndexByConversation.clear()

        snapshot.messages.forEach { (messageId, pm) ->
            val msg = ConversationMessage(
                messageId = pm.messageId,
                conversationId = pm.conversationId,
                direction = pm.direction,
                payload = pm.payload,
                sendIndex = pm.sendIndex,
                receiveIndex = pm.receiveIndex,
                state = pm.state,
                timestamps = pm.timestamps,
            )
            entriesByMessageId[messageId] = Entry(message = msg, failureReason = pm.failureReason)
        }

        snapshot.conversations.values.forEach { convo ->
            timelineByConversation[convo.conversationId] = ArrayList(convo.orderedMessageIds)
        }

        nextSendIndexByConversation.putAll(snapshot.nextSendIndexByConversation)
        nextReceiveIndexByConversation.putAll(snapshot.nextReceiveIndexByConversation)

        val now = clock.nowMs()
        debugTrace.onRestoreApplied(
            conversationCount = timelineByConversation.size,
            messageCount = entriesByMessageId.size,
            elapsedMs = now,
        )

        // Verify restore ordering + index monotonicity.
        timelineByConversation.keys.forEach { cid ->
            val ok = verifyOrderingForConversation(cid, now)
            debugTrace.onRestoreVerification(
                conversationId = cid,
                ok = ok,
                detail = if (ok) "ok" else "ordering/index violation",
                elapsedMs = now,
            )
        }
        // Restore must not trigger sends or transitions.
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        entriesByMessageId.clear()
        timelineByConversation.clear()
        nextSendIndexByConversation.clear()
        nextReceiveIndexByConversation.clear()
        debugTrace.close()
    }

    private fun transition(messageId: String, target: ConversationMessage.State, elapsedMs: Long): Boolean {
        val entry = entriesByMessageId[messageId] ?: return false

        // Enforce direction-appropriate transitions.
        val direction = entry.message.direction
        when (target) {
            ConversationMessage.State.SENT,
            ConversationMessage.State.ACKED,
            -> if (direction != ConversationMessage.Direction.OUTBOUND) {
                debugTrace.onOrderingViolation(
                    conversationId = entry.message.conversationId,
                    messageId = messageId,
                    detail = "invalid_transition target=${target.name} direction=${direction.name}",
                    elapsedMs = elapsedMs,
                )
                return false
            }

            ConversationMessage.State.RECEIVED -> {
                // Allowed for inbound; for outbound we ignore.
                if (direction != ConversationMessage.Direction.INBOUND) {
                    debugTrace.onOrderingViolation(
                        conversationId = entry.message.conversationId,
                        messageId = messageId,
                        detail = "invalid_transition target=RECEIVED direction=${direction.name}",
                        elapsedMs = elapsedMs,
                    )
                    return false
                }
            }

            ConversationMessage.State.QUEUED,
            ConversationMessage.State.FAILED_TERMINAL,
            -> Unit
        }

        val prev = entry.message.state
        if (prev == target) return true

        // Prevent regression.
        if (rank(target) < rank(prev)) return true

        // Terminal is terminal.
        if (prev == ConversationMessage.State.FAILED_TERMINAL) return true

        val updated = entry.message.copy(
            state = target,
            timestamps = updateTimestamps(entry.message.timestamps, prev, target, elapsedMs),
        )
        entry.message = updated
        debugTrace.onTransition(messageId, from = prev.name, to = target.name, elapsedMs = elapsedMs)
        return true
    }

    private fun updateTimestamps(
        ts: ConversationMessage.Timestamps,
        prev: ConversationMessage.State,
        next: ConversationMessage.State,
        elapsedMs: Long,
    ): ConversationMessage.Timestamps {
        var out = ts.copy(updatedAtElapsedMs = elapsedMs)
        when (next) {
            ConversationMessage.State.QUEUED -> if (out.queuedAtElapsedMs == null) out = out.copy(queuedAtElapsedMs = elapsedMs)
            ConversationMessage.State.SENT -> if (out.sentAtElapsedMs == null) out = out.copy(sentAtElapsedMs = elapsedMs)
            ConversationMessage.State.RECEIVED -> if (out.receivedAtElapsedMs == null) out = out.copy(receivedAtElapsedMs = elapsedMs)
            ConversationMessage.State.ACKED -> if (out.ackedAtElapsedMs == null) out = out.copy(ackedAtElapsedMs = elapsedMs)
            ConversationMessage.State.FAILED_TERMINAL -> if (out.failedAtElapsedMs == null) out = out.copy(failedAtElapsedMs = elapsedMs)
        }

        // If we are transitioning from QUEUED to SENT and never set queuedAt, backfill.
        if (prev == ConversationMessage.State.QUEUED && out.queuedAtElapsedMs == null) {
            out = out.copy(queuedAtElapsedMs = ts.createdAtElapsedMs)
        }

        return out
    }

    private fun verifyOrderingForConversation(conversationId: String, elapsedMs: Long): Boolean {
        val ids = timelineByConversation[conversationId] ?: return true
        var lastOutbound = -1
        var lastInbound = -1
        var ok = true
        for (id in ids) {
            val m = entriesByMessageId[id]?.message ?: continue
            when (m.direction) {
                ConversationMessage.Direction.OUTBOUND -> {
                    val idx = m.sendIndex
                    if (idx == null) {
                        ok = false
                        debugTrace.onOrderingViolation(conversationId, id, "missing_sendIndex", elapsedMs)
                    } else if (idx <= lastOutbound) {
                        ok = false
                        debugTrace.onOrderingViolation(conversationId, id, "sendIndex_not_increasing prev=$lastOutbound now=$idx", elapsedMs)
                    } else {
                        lastOutbound = idx
                    }
                }

                ConversationMessage.Direction.INBOUND -> {
                    val idx = m.receiveIndex
                    if (idx == null) {
                        ok = false
                        debugTrace.onOrderingViolation(conversationId, id, "missing_receiveIndex", elapsedMs)
                    } else if (idx <= lastInbound) {
                        ok = false
                        debugTrace.onOrderingViolation(conversationId, id, "receiveIndex_not_increasing prev=$lastInbound now=$idx", elapsedMs)
                    } else {
                        lastInbound = idx
                    }
                }
            }
        }
        return ok
    }

    private fun rank(state: ConversationMessage.State): Int = when (state) {
        ConversationMessage.State.QUEUED -> 0
        ConversationMessage.State.SENT -> 1
        ConversationMessage.State.RECEIVED -> 1
        ConversationMessage.State.ACKED -> 2
        ConversationMessage.State.FAILED_TERMINAL -> 3
    }
}
