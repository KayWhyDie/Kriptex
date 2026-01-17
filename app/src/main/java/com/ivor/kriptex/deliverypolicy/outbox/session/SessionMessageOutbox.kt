package com.ivor.kriptex.deliverypolicy.outbox.session

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.DeliveryMode
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.decision.DeliveryDecisionEngine
import com.ivor.kriptex.deliverypolicy.diagnostics.MessageOutboxDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.MessageOutboxSessionDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.MessageOutboxPersistenceDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpMessageOutboxDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpMessageOutboxSessionDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpMessageOutboxPersistenceDebugTrace
import com.ivor.kriptex.deliverypolicy.outbox.DeliveryAttemptResult
import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult
import com.ivor.kriptex.deliverypolicy.outbox.MessageOutbox
import com.ivor.kriptex.deliverypolicy.outbox.OutboxItem
import com.ivor.kriptex.deliverypolicy.outbox.OutboxSnapshot
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.persistence.PersistedMessageOutboxSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedOutboxMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Message outbox with explicit delivery sessions.
 *
 * This variant models asynchronous acknowledgments without implementing transport.
 * It does not change the behavior of the existing [com.ivor.kriptex.deliverypolicy.outbox.DefaultMessageOutbox].
 */
class SessionMessageOutbox(
    private val decisionEngine: DeliveryDecisionEngine,
    private val sender: DeliveryStrategySessionSender,
    private val clock: Clock = MonotonicClock,
    private val debugTrace: MessageOutboxDebugTrace = NoOpMessageOutboxDebugTrace,
    private val sessionDebugTrace: MessageOutboxSessionDebugTrace = NoOpMessageOutboxSessionDebugTrace,
    private val persistenceDebugTrace: MessageOutboxPersistenceDebugTrace = NoOpMessageOutboxPersistenceDebugTrace,
) : MessageOutbox {

    private data class Entry(
        val message: OutgoingMessage,
        var status: OutboxItem.Status,
        var openSessionId: String? = null,
        val enqueuedMode: DeliveryMode,
        val enqueuedPassiveQueueReason: String?,
    )

    private val listeners = LinkedHashSet<(OutboxSnapshot) -> Unit>()
    private val entries = LinkedHashMap<String, Entry>()

    private var closed = false
    private var currentStrategy: DeliveryStrategy = decisionEngine.strategy

    private val _snapshotFlow = MutableStateFlow(buildSnapshot())
    override val snapshotFlow: StateFlow<OutboxSnapshot> = _snapshotFlow.asStateFlow()

    override val snapshot: OutboxSnapshot
        get() = _snapshotFlow.value

    private val unsubscribeStrategy = decisionEngine.addListener { newStrategy ->
        if (closed) return@addListener
        val prev = currentStrategy
        currentStrategy = newStrategy
        debugTrace.onStrategyChanged(prev, newStrategy, elapsedMs())
        attemptEligible(reason = "strategy_changed")
    }

    override fun addListener(listener: (OutboxSnapshot) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(snapshot)
        return { listeners.remove(listener) }
    }

    override fun enqueue(message: OutgoingMessage): EnqueueResult {
        if (closed) return EnqueueResult.AlreadyEnqueued
        if (entries.containsKey(message.messageId)) return EnqueueResult.AlreadyEnqueued

        val mode = currentStrategy.mode
        val passiveReason = (currentStrategy as? PassiveDelivery)?.queueReason?.name

        entries[message.messageId] = Entry(
            message = message,
            status = OutboxItem.Status.QUEUED,
            enqueuedMode = mode,
            enqueuedPassiveQueueReason = passiveReason,
        )
        debugTrace.onEnqueued(messageId = message.messageId, elapsedMs = elapsedMs())
        publish()

        attemptSingle(message.messageId, reason = "enqueue")
        return EnqueueResult.Enqueued
    }

    override fun notifyDelivered(messageId: String): Boolean {
        val removed = entries.remove(messageId) ?: return false
        debugTrace.onDelivered(messageId = removed.message.messageId, elapsedMs = elapsedMs())
        publish()
        attemptEligible(reason = "delivered")
        return true
    }

    override fun notifyFailed(messageId: String, retryable: Boolean, reason: String?): Boolean {
        val entry = entries[messageId] ?: return false
        entry.status = if (retryable) OutboxItem.Status.FAILED_RETRYABLE else OutboxItem.Status.FAILED_TERMINAL
        debugTrace.onFailed(messageId = messageId, retryable = retryable, reason = reason, elapsedMs = elapsedMs())
        publish()
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        unsubscribeStrategy()
        listeners.clear()
        entries.clear()
        debugTrace.close()
        sessionDebugTrace.close()
    }

    private fun attemptEligible(reason: String) {
        val ids = entries.keys.toList()
        ids.forEach { attemptSingle(it, reason = reason) }
    }

    private fun attemptSingle(messageId: String, reason: String) {
        val entry = entries[messageId] ?: return

        // Prevent duplication: do not attempt if a session is already open.
        if (entry.openSessionId != null) return

        val eligible = when (entry.status) {
            OutboxItem.Status.QUEUED,
            OutboxItem.Status.DEFERRED,
            OutboxItem.Status.FAILED_RETRYABLE,
            -> true

            OutboxItem.Status.IN_FLIGHT,
            OutboxItem.Status.FAILED_TERMINAL,
            -> false
        }
        if (!eligible) return

        debugTrace.onAttempt(
            messageId = entry.message.messageId,
            strategy = currentStrategy,
            reason = reason,
            elapsedMs = elapsedMs(),
        )

        data class PendingCompletion(
            val sessionId: String,
            val messageId: String,
            val result: DeliveryAttemptResult,
            val durationMs: Long,
        )

        // Sessions may complete synchronously inside sender.startSession.
        // Buffer completion until we've recorded openSessionId.
        var startedSessionId: String? = null
        var pending: PendingCompletion? = null

        val completionSink = DeliverySessionCompletionSink { sessionId, completedMessageId, result, durationMs ->
            if (startedSessionId == null) {
                pending = PendingCompletion(
                    sessionId = sessionId,
                    messageId = completedMessageId,
                    result = result,
                    durationMs = durationMs,
                )
                return@DeliverySessionCompletionSink
            }
            onSessionCompleted(sessionId, completedMessageId, result, durationMs)
        }

        val session = sender.startSession(
            strategy = currentStrategy,
            message = entry.message,
            clock = clock,
            completionSink = completionSink,
        )

        startedSessionId = session.sessionId
        entry.openSessionId = session.sessionId
        entry.status = OutboxItem.Status.IN_FLIGHT
        sessionDebugTrace.onSessionCreated(messageId = entry.message.messageId, sessionId = session.sessionId, elapsedMs = elapsedMs())
        publish()

        // Replay synchronous completion after openSessionId is set.
        pending?.let { onSessionCompleted(it.sessionId, it.messageId, it.result, it.durationMs) }
    }

    private fun onSessionCompleted(
        sessionId: String,
        messageId: String,
        result: DeliveryAttemptResult,
        durationMs: Long,
    ) {
        val entry = entries[messageId] ?: return
        if (entry.openSessionId != sessionId) {
            // Ignore stale/mismatched completion.
            return
        }

        entry.openSessionId = null

        val outcomeLabel = when (result) {
            DeliveryAttemptResult.Accepted -> "DELIVERED"
            is DeliveryAttemptResult.Deferred -> "DEFERRED"
            is DeliveryAttemptResult.Failed -> if (result.retryable) "FAILED_RETRYABLE" else "FAILED_TERMINAL"
        }

        sessionDebugTrace.onSessionCompleted(
            messageId = messageId,
            sessionId = sessionId,
            outcome = outcomeLabel,
            durationMs = durationMs,
            elapsedMs = elapsedMs(),
        )

        when (result) {
            DeliveryAttemptResult.Accepted -> {
                entries.remove(messageId)
                debugTrace.onDelivered(messageId = messageId, elapsedMs = elapsedMs())
            }

            is DeliveryAttemptResult.Deferred -> {
                entry.status = OutboxItem.Status.DEFERRED
                debugTrace.onAttemptResult(messageId = messageId, result = "DEFERRED", detail = result.reason, elapsedMs = elapsedMs())
            }

            is DeliveryAttemptResult.Failed -> {
                entry.status = if (result.retryable) OutboxItem.Status.FAILED_RETRYABLE else OutboxItem.Status.FAILED_TERMINAL
                debugTrace.onAttemptResult(
                    messageId = messageId,
                    result = if (result.retryable) "FAILED_RETRYABLE" else "FAILED_TERMINAL",
                    detail = result.reason,
                    elapsedMs = elapsedMs(),
                )
            }
        }

        publish()
    }

    private fun publish() {
        val snapshot = buildSnapshot()
        _snapshotFlow.value = snapshot
        listeners.toList().forEach { it(snapshot) }
    }

    private fun buildSnapshot(): OutboxSnapshot {
        val items = entries.values.map {
            OutboxItem(
                messageId = it.message.messageId,
                chatId = it.message.chatId,
                status = it.status,
                enqueueElapsedMs = it.message.enqueueElapsedMs,
            )
        }
        return OutboxSnapshot(size = items.size, items = items)
    }

    private fun elapsedMs(): Long = clock.nowMs()

    override fun snapshot(): PersistedMessageOutboxSnapshot {
        val capturedAt = clock.nowMs()
        val messages = entries.values.map {
            PersistedOutboxMessage(
                messageId = it.message.messageId,
                chatId = it.message.chatId,
                payload = it.message.payload.copyOf(),
                enqueueElapsedMs = it.message.enqueueElapsedMs,
                enqueuedMode = it.enqueuedMode,
                enqueuedPassiveQueueReason = it.enqueuedPassiveQueueReason,
                statusAtSnapshot = it.status,
            )
        }
        persistenceDebugTrace.onSnapshotBuilt(messageCount = messages.size, capturedAtElapsedMs = capturedAt)
        return PersistedMessageOutboxSnapshot(
            capturedAtElapsedMs = capturedAt,
            messages = messages,
        )
    }

    override fun restore(snapshot: PersistedMessageOutboxSnapshot) {
        if (closed) return

        entries.clear()

        var dropped = 0
        snapshot.messages.forEach { m ->
            if (entries.containsKey(m.messageId)) {
                dropped++
                return@forEach
            }
            entries[m.messageId] = Entry(
                message = OutgoingMessage(
                    messageId = m.messageId,
                    chatId = m.chatId,
                    payload = m.payload.copyOf(),
                    enqueueElapsedMs = m.enqueueElapsedMs,
                ),
                // Restore semantics: never resurrect IN_FLIGHT.
                status = OutboxItem.Status.DEFERRED,
                openSessionId = null,
                enqueuedMode = m.enqueuedMode,
                enqueuedPassiveQueueReason = m.enqueuedPassiveQueueReason,
            )
        }

        val now = clock.nowMs()
        if (dropped > 0) persistenceDebugTrace.onRestoreDeduplicated(droppedCount = dropped, capturedAtElapsedMs = now)
        persistenceDebugTrace.onRestoreApplied(messageCount = entries.size, capturedAtElapsedMs = now)

        publish()
        // Intentionally do not attempt delivery on restore.
    }
}
