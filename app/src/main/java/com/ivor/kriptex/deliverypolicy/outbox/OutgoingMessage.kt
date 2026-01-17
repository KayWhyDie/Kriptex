package com.ivor.kriptex.deliverypolicy.outbox

/**
 * Immutable outgoing message descriptor.
 *
 * - [payload] is opaque to the outbox (no parsing, no crypto).
 * - [enqueueElapsedMs] is relative/monotonic (caller-defined, typically from a monotonic clock).
 */
data class OutgoingMessage(
    val messageId: String,
    val chatId: String,
    val payload: ByteArray,
    val enqueueElapsedMs: Long,
)
