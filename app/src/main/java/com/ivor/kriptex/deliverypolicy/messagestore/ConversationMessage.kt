package com.ivor.kriptex.deliverypolicy.messagestore

/**
 * Authoritative conversation-scoped message model.
 *
 * - payload is opaque
 * - timestamps are monotonic (elapsed time)
 */
data class ConversationMessage(
    val messageId: String,
    val conversationId: String,
    val direction: Direction,
    val payload: ByteArray,
    /** Sequential ordering for outbound messages within a conversation. */
    val sendIndex: Int?,
    /** Sequential ordering for inbound messages within a conversation. */
    val receiveIndex: Int?,
    val state: State,
    val timestamps: Timestamps,
) {
    enum class Direction {
        INBOUND,
        OUTBOUND,
    }

    enum class State {
        QUEUED,
        SENT,
        RECEIVED,
        ACKED,
        FAILED_TERMINAL,
    }

    data class Timestamps(
        val createdAtElapsedMs: Long,
        val updatedAtElapsedMs: Long,
        val queuedAtElapsedMs: Long? = null,
        val sentAtElapsedMs: Long? = null,
        val receivedAtElapsedMs: Long? = null,
        val ackedAtElapsedMs: Long? = null,
        val failedAtElapsedMs: Long? = null,
    )
}
