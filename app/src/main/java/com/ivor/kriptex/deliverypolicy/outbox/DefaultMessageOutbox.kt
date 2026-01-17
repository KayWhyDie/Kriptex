package com.ivor.kriptex.deliverypolicy.outbox

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.DeliveryMode
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.decision.DeliveryDecisionEngine
import com.ivor.kriptex.deliverypolicy.diagnostics.MessageOutboxDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.MessageOutboxPersistenceDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpMessageOutboxDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpMessageOutboxPersistenceDebugTrace
import com.ivor.kriptex.deliverypolicy.persistence.PersistedMessageOutboxSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedOutboxMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory, strategy-agnostic outbox.
 *
 * Guarantees:
 * - `enqueue()` is non-blocking and does not depend on current strategy.
 * - A message is removed ONLY via [notifyDelivered].
 * - Outbox never attempts the same message concurrently (prevents duplication at outbox level).
 * - On strategy changes, eligible pending messages are re-attempted.
 */
class DefaultMessageOutbox(
    private val decisionEngine: DeliveryDecisionEngine,
    private val sender: DeliveryStrategySender,
    private val clock: Clock = MonotonicClock,
    private val debugTrace: MessageOutboxDebugTrace = NoOpMessageOutboxDebugTrace,
    private val persistenceDebugTrace: MessageOutboxPersistenceDebugTrace = NoOpMessageOutboxPersistenceDebugTrace,
) : MessageOutbox {

    private data class Entry(
        val message: OutgoingMessage,
        var status: OutboxItem.Status,
        val enqueuedMode: DeliveryMode,
        val enqueuedPassiveQueueReason: String?,
    )

    private val listeners = LinkedHashSet<(OutboxSnapshot) -> Unit>()
    private val entries = LinkedHashMap<String, Entry>()

    private var closed = false

    private val _snapshotFlow = MutableStateFlow(buildSnapshot())
    override val snapshotFlow: StateFlow<OutboxSnapshot> = _snapshotFlow.asStateFlow()

    override val snapshot: OutboxSnapshot
        get() = _snapshotFlow.value

    private var currentStrategy: DeliveryStrategy = decisionEngine.strategy

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

        val existing = entries[message.messageId]
        if (existing != null) return EnqueueResult.AlreadyEnqueued

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

        // Attempt immediately, but never remove here.
        attemptSingle(message.messageId, reason = "enqueue")

        return EnqueueResult.Enqueued
    }

    override fun notifyDelivered(messageId: String): Boolean {
        val removed = entries.remove(messageId) ?: return false
        debugTrace.onDelivered(messageId = removed.message.messageId, elapsedMs = elapsedMs())
        publish()
        // After a delivery, we can try other eligible items.
        attemptEligible(reason = "delivered")
        return true
    }

    override fun notifyFailed(messageId: String, retryable: Boolean, reason: String?): Boolean {
        val entry = entries[messageId] ?: return false
        entry.status = if (retryable) OutboxItem.Status.FAILED_RETRYABLE else OutboxItem.Status.FAILED_TERMINAL
        debugTrace.onFailed(messageId = messageId, retryable = retryable, reason = reason, elapsedMs = elapsedMs())
        publish()

        if (retryable) {
            attemptSingle(messageId, reason = "failed_retryable")
        }

        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        unsubscribeStrategy()
        listeners.clear()
        entries.clear()
        debugTrace.close()
    }

    private fun attemptEligible(reason: String) {
        // Snapshot ids to avoid concurrent modification.
        val ids = entries.keys.toList()
        ids.forEach { attemptSingle(it, reason = reason) }
    }

    private fun attemptSingle(messageId: String, reason: String) {
        val entry = entries[messageId] ?: return

        // Prevent duplication: never attempt while in-flight.
        if (entry.status == OutboxItem.Status.IN_FLIGHT) return

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

        val result = sender.attemptSend(currentStrategy, entry.message)

        when (result) {
            DeliveryAttemptResult.Accepted -> {
                entry.status = OutboxItem.Status.IN_FLIGHT
                debugTrace.onAttemptResult(
                    messageId = entry.message.messageId,
                    result = "ACCEPTED",
                    elapsedMs = elapsedMs(),
                )
            }

            is DeliveryAttemptResult.Deferred -> {
                entry.status = OutboxItem.Status.DEFERRED
                debugTrace.onAttemptResult(
                    messageId = entry.message.messageId,
                    result = "DEFERRED",
                    detail = result.reason,
                    elapsedMs = elapsedMs(),
                )
            }

            is DeliveryAttemptResult.Failed -> {
                entry.status = if (result.retryable) OutboxItem.Status.FAILED_RETRYABLE else OutboxItem.Status.FAILED_TERMINAL
                debugTrace.onAttemptResult(
                    messageId = entry.message.messageId,
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
        val snapshotListeners = listeners.toList()
        snapshotListeners.forEach { it(snapshot) }
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

        // Dedupe by messageId; restore semantics: no IN_FLIGHT on restore.
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
                status = OutboxItem.Status.DEFERRED,
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
