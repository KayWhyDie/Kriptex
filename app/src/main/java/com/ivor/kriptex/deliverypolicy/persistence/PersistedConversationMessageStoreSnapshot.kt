package com.ivor.kriptex.deliverypolicy.persistence

import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessage

/**
 * Persistable snapshot of the conversation message store.
 *
 * Pure state only:
 * - per-conversation stable timeline ordering
 * - messages (including indexes, states, timestamps)
 * - next index counters
 */
data class PersistedConversationMessageStoreSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val conversations: Map<String, PersistedConversationTimeline>,
    val messages: Map<String, PersistedConversationMessage>,
    val nextSendIndexByConversation: Map<String, Int>,
    val nextReceiveIndexByConversation: Map<String, Int>,
)

data class PersistedConversationTimeline(
    val conversationId: String,
    /** Stable ordering of messageIds for mixed inbound/outbound timeline. */
    val orderedMessageIds: List<String>,
)

data class PersistedConversationMessage(
    val messageId: String,
    val conversationId: String,
    val direction: ConversationMessage.Direction,
    val payload: ByteArray,
    val sendIndex: Int?,
    val receiveIndex: Int?,
    val state: ConversationMessage.State,
    val timestamps: ConversationMessage.Timestamps,
    val failureReason: String? = null,
)
