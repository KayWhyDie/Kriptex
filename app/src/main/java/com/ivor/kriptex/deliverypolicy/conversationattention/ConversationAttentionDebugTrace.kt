package com.ivor.kriptex.deliverypolicy.conversationattention

import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot

interface ConversationAttentionDebugTrace {
    fun onDecision(
        conversationId: String,
        attentionState: ConversationAttentionState,
        notification: String,
        unread: String,
        reason: String,
        lastActivityTimestamp: Long,
    )

    fun onNotify(conversationId: String, reason: String, lastActivityTimestamp: Long)

    fun onCancel(conversationId: String, reason: String)
}

object NoOpConversationAttentionDebugTrace : ConversationAttentionDebugTrace {
    override fun onDecision(
        conversationId: String,
        attentionState: ConversationAttentionState,
        notification: String,
        unread: String,
        reason: String,
        lastActivityTimestamp: Long,
    ) = Unit

    override fun onNotify(conversationId: String, reason: String, lastActivityTimestamp: Long) = Unit

    override fun onCancel(conversationId: String, reason: String) = Unit
}
