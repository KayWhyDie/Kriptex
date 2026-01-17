package com.ivor.kriptex.deliverypolicy.conversationstate

interface ConversationStateDebugTrace {
    /**
     * Called when a snapshot is derived. Reasons are opaque strings intended for debugging.
     * Must not include payloads, keys, or sensitive content.
     */
    fun onDerived(
        conversationId: String,
        conversationType: ConversationType,
        health: ConversationHealth,
        healthReason: String?,
        encryptionStatus: ConversationEncryptionStatus,
        encryptionReason: String?,
        pendingMessageCount: Int,
        lastActivityTimestamp: Long,
    )
}

object NoOpConversationStateDebugTrace : ConversationStateDebugTrace {
    override fun onDerived(
        conversationId: String,
        conversationType: ConversationType,
        health: ConversationHealth,
        healthReason: String?,
        encryptionStatus: ConversationEncryptionStatus,
        encryptionReason: String?,
        pendingMessageCount: Int,
        lastActivityTimestamp: Long,
    ) = Unit
}
