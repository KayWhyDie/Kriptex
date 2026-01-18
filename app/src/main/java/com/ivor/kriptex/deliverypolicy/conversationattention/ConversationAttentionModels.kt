package com.ivor.kriptex.deliverypolicy.conversationattention

import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot

enum class ConversationAttentionState {
    VISIBLE,
    FOREGROUND_BACKGROUND,
    BACKGROUND,
}

enum class AppLifecycleState {
    FOREGROUND,
    BACKGROUND,
}

sealed interface NotificationDecision {
    data object None : NotificationDecision

    /** Show a user-facing notification (or equivalent). */
    data class Notify(val conversationId: String, val snapshot: ConversationSnapshot) : NotificationDecision

    /** Cancel any active notification for this conversation. */
    data class Cancel(val conversationId: String) : NotificationDecision
}

enum class UnreadDecision {
    INCREMENT,
    SUPPRESS,
}

data class ConversationAttentionDecision(
    val conversationId: String,
    val attentionState: ConversationAttentionState,
    val snapshot: ConversationSnapshot,
    val notification: NotificationDecision,
    val unread: UnreadDecision,
    /** Opaque reason for diagnostics; must not include payloads/keys/identities. */
    val reason: String,
)
