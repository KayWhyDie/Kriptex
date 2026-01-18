package com.ivor.kriptex.deliverypolicy.conversationattention

import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot

/**
 * Notification boundary for conversation attention decisions.
 *
 * No Android framework APIs should be used in implementations inside core logic.
 */
interface NotificationSink {
    fun showNotification(conversationId: String, snapshot: ConversationSnapshot)

    fun cancelNotification(conversationId: String)
}
