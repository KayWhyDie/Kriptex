package com.ivor.kriptex.deliverypolicy.messagestore

import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationMessageStoreSnapshot

interface ConversationMessageStore {

    fun appendOutbound(messageId: String, conversationId: String, payload: ByteArray, elapsedMs: Long): AppendResult

    fun appendInbound(messageId: String, conversationId: String, payload: ByteArray, elapsedMs: Long): AppendResult

    fun markSent(messageId: String, elapsedMs: Long): Boolean

    fun markReceived(messageId: String, elapsedMs: Long): Boolean

    fun markAcked(messageId: String, elapsedMs: Long): Boolean

    fun markFailed(messageId: String, elapsedMs: Long, reason: String? = null): Boolean

    fun conversationTimeline(conversationId: String): ConversationTimeline

    fun message(messageId: String): ConversationMessage?

    fun snapshot(): PersistedConversationMessageStoreSnapshot

    fun restore(snapshot: PersistedConversationMessageStoreSnapshot)

    fun close()
}

sealed interface AppendResult {
    data object Appended : AppendResult
    data object DuplicateIgnored : AppendResult
}

data class ConversationTimeline(
    val conversationId: String,
    /** Deterministic, immutable ordering. */
    val messages: List<ConversationMessage>,
)
