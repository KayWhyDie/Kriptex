package com.ivor.kriptex.deliverypolicy.outbox

import com.ivor.kriptex.deliverypolicy.persistence.PersistedMessageOutboxSnapshot
import kotlinx.coroutines.flow.StateFlow

interface MessageOutbox {
    val snapshotFlow: StateFlow<OutboxSnapshot>
    val snapshot: OutboxSnapshot

    /**
     * Persistable snapshot.
     *
     * Must be pure data.
     * Must not include runtime-only state like IN_FLIGHT sessions.
     */
    fun snapshot(): PersistedMessageOutboxSnapshot = PersistedMessageOutboxSnapshot(
        capturedAtElapsedMs = 0L,
        messages = emptyList(),
    )

    /**
     * Restore from a persistable snapshot.
     *
     * Restore semantics:
     * - No message resumes as IN_FLIGHT.
     * - All restored messages are DEFERRED until a later strategy re-evaluation triggers attempts.
     * - Must not trigger automatic active sends.
     */
    fun restore(snapshot: PersistedMessageOutboxSnapshot) = Unit

    fun enqueue(message: OutgoingMessage): EnqueueResult

    /** Removes the message from the outbox. This is the only removal mechanism. */
    fun notifyDelivered(messageId: String): Boolean

    /** Marks a message as failed; retryable failures remain eligible for future attempts. */
    fun notifyFailed(messageId: String, retryable: Boolean, reason: String? = null): Boolean

    /** Callback-based observation. Listener is immediately invoked with the current snapshot. */
    fun addListener(listener: (OutboxSnapshot) -> Unit): () -> Unit

    fun close()
}

sealed interface EnqueueResult {
    data object Enqueued : EnqueueResult
    data object AlreadyEnqueued : EnqueueResult
}

data class OutboxSnapshot(
    val size: Int,
    val items: List<OutboxItem>,
)

data class OutboxItem(
    val messageId: String,
    val chatId: String,
    val status: Status,
    val enqueueElapsedMs: Long,
) {
    enum class Status {
        QUEUED,
        IN_FLIGHT,
        DEFERRED,
        FAILED_RETRYABLE,
        FAILED_TERMINAL,
    }
}
