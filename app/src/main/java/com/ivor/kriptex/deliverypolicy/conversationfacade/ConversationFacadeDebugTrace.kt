package com.ivor.kriptex.deliverypolicy.conversationfacade

/**
 * Diagnostics for [ConversationFacade].
 *
 * Must not include payloads/keys/identities.
 */
interface ConversationFacadeDebugTrace {
    fun onViewUpdated(
        conversationId: String,
        reason: String,
        changedComponents: Set<String>,
    )
}

data object NoOpConversationFacadeDebugTrace : ConversationFacadeDebugTrace {
    override fun onViewUpdated(conversationId: String, reason: String, changedComponents: Set<String>) = Unit
}
