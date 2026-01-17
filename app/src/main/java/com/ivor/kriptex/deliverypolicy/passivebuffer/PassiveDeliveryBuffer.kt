package com.ivor.kriptex.deliverypolicy.passivebuffer

import com.ivor.kriptex.deliverypolicy.persistence.PersistedPassiveDeliveryBufferSnapshot
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySession

/**
 * In-memory store-and-forward model for passive delivery.
 *
 * The buffer does not know about connection state; it is driven by higher layers.
 * It must not duplicate or lose messages.
 */
interface PassiveDeliveryBuffer {
    val size: Int

    /**
     * Persistable snapshot of buffered messages.
     *
     * Must be pure data.
     * Runtime-only session identity is not persisted.
     */
    fun snapshot(): PersistedPassiveDeliveryBufferSnapshot = PersistedPassiveDeliveryBufferSnapshot(
        capturedAtElapsedMs = 0L,
        messages = emptyList(),
    )

    /**
     * Restore from a persistable snapshot.
     *
     * Restore semantics:
     * - Buffer availability must reset to unavailable.
     * - Restored messages must not be duplicated.
     */
    fun restore(snapshot: PersistedPassiveDeliveryBufferSnapshot) = Unit

    fun markAvailable()

    fun markUnavailable()

    /**
     * Enqueues a message for later delivery while passive.
     * Must be idempotent by [OutgoingMessage.messageId].
     */
    fun enqueue(message: OutgoingMessage, session: DeliverySession): BufferEnqueueResult

    /**
     * Drains buffered messages if available; otherwise returns empty.
     * Drained messages are removed from the buffer.
     */
    fun drainReady(): List<BufferedMessage>
}

sealed interface BufferEnqueueResult {
    data object Enqueued : BufferEnqueueResult
    data object AlreadyBuffered : BufferEnqueueResult
}

data class BufferedMessage(
    val messageId: String,
    val payload: ByteArray,
    val enqueueElapsedMs: Long,
    val sessionId: String,
)
