package com.ivor.kriptex.deliverypolicy.persistence

/**
 * Persistable snapshot of passive delivery buffering.
 *
 * Notes:
 * - Payload is treated as opaque bytes (not inspected).
 * - Runtime-only session identity is intentionally not persisted.
 */
data class PersistedPassiveDeliveryBufferSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val messages: List<PersistedBufferedMessage>,
)

data class PersistedBufferedMessage(
    val messageId: String,
    val payload: ByteArray,
    val enqueueElapsedMs: Long,
)
