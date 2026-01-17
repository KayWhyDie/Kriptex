package com.ivor.kriptex.deliverypolicy.inbound

/**
 * Transport-agnostic inbound message.
 *
 * - [senderId] is opaque.
 * - [payload] is opaque.
 * - [receivedAtElapsedMs] is monotonic time.
 */
data class InboundMessage(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val payload: ByteArray,
    val receivedAtElapsedMs: Long,
    val kind: Kind = Kind.DATA,
) {
    enum class Kind {
        /** Normal inbound message. */
        DATA,

        /** Inbound ACK for an outbound message identified by [messageId]. */
        ACK,
    }
}
