package com.ivor.kriptex.deliverypolicy.persistence

import com.ivor.kriptex.deliverypolicy.DeliveryMode
import com.ivor.kriptex.deliverypolicy.outbox.OutboxItem

/**
 * Persistable snapshot of the outbox.
 *
 * Notes:
 * - Payload is treated as opaque bytes (not inspected).
 * - Runtime-only state (IN_FLIGHT sessions) is intentionally not persisted.
 * - On restore, implementations must not resurrect IN_FLIGHT; items must resume as DEFERRED.
 */
data class PersistedMessageOutboxSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val messages: List<PersistedOutboxMessage>,
)

data class PersistedOutboxMessage(
    val messageId: String,
    val chatId: String,
    val payload: ByteArray,
    val enqueueElapsedMs: Long,

    /** Strategy mode at enqueue time (best-effort, captured by the outbox). */
    val enqueuedMode: DeliveryMode,

    /** If enqueued while passive, record the queue reason (string-only, no peer ids). */
    val enqueuedPassiveQueueReason: String? = null,

    /** Last observed status at snapshot time. Restore semantics may ignore this. */
    val statusAtSnapshot: OutboxItem.Status,
)
