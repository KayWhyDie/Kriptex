package com.ivor.kriptex.deliverypolicy.diagnostics

import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessage

/**
 * Developer-only diagnostics for ConversationMessageStore.
 *
 * Safety:
 * - No payloads
 * - No peer identifiers
 * - No keys
 */
interface ConversationMessageStoreDebugTrace : AutoCloseable {

    fun onAppended(
        messageId: String,
        conversationId: String,
        direction: ConversationMessage.Direction,
        sendIndex: Int?,
        receiveIndex: Int?,
        state: ConversationMessage.State,
        elapsedMs: Long,
    )

    fun onDuplicateIgnored(messageId: String, conversationId: String, elapsedMs: Long)

    fun onTransition(messageId: String, from: String, to: String, elapsedMs: Long)

    fun onOrderingViolation(conversationId: String, messageId: String, detail: String, elapsedMs: Long)

    fun onSnapshotBuilt(conversationCount: Int, messageCount: Int, elapsedMs: Long)

    fun onRestoreApplied(conversationCount: Int, messageCount: Int, elapsedMs: Long)

    fun onRestoreVerification(conversationId: String, ok: Boolean, detail: String, elapsedMs: Long)

    fun dumpDebugReport(): String
}

object NoOpConversationMessageStoreDebugTrace : ConversationMessageStoreDebugTrace {
    override fun onAppended(
        messageId: String,
        conversationId: String,
        direction: ConversationMessage.Direction,
        sendIndex: Int?,
        receiveIndex: Int?,
        state: ConversationMessage.State,
        elapsedMs: Long,
    ) = Unit

    override fun onDuplicateIgnored(messageId: String, conversationId: String, elapsedMs: Long) = Unit
    override fun onTransition(messageId: String, from: String, to: String, elapsedMs: Long) = Unit
    override fun onOrderingViolation(conversationId: String, messageId: String, detail: String, elapsedMs: Long) = Unit
    override fun onSnapshotBuilt(conversationCount: Int, messageCount: Int, elapsedMs: Long) = Unit
    override fun onRestoreApplied(conversationCount: Int, messageCount: Int, elapsedMs: Long) = Unit
    override fun onRestoreVerification(conversationId: String, ok: Boolean, detail: String, elapsedMs: Long) = Unit
    override fun dumpDebugReport(): String = "ConversationMessageStore diagnostics disabled."
    override fun close() = Unit
}

class DefaultConversationMessageStoreDebugTrace(
    private val maxEntries: Int = 500,
) : ConversationMessageStoreDebugTrace {

    private data class Entry(val elapsedMs: Long, val message: String)

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

    @Synchronized
    override fun onAppended(
        messageId: String,
        conversationId: String,
        direction: ConversationMessage.Direction,
        sendIndex: Int?,
        receiveIndex: Int?,
        state: ConversationMessage.State,
        elapsedMs: Long,
    ) {
        record(
            elapsedMs,
            "APPEND conversationId=$conversationId direction=${direction.name} sendIndex=$sendIndex receiveIndex=$receiveIndex state=${state.name} messageId=$messageId",
        )
    }

    @Synchronized
    override fun onDuplicateIgnored(messageId: String, conversationId: String, elapsedMs: Long) {
        record(elapsedMs, "DUPLICATE_IGNORED conversationId=$conversationId messageId=$messageId")
    }

    @Synchronized
    override fun onTransition(messageId: String, from: String, to: String, elapsedMs: Long) {
        record(elapsedMs, "TRANSITION messageId=$messageId from=$from to=$to")
    }

    @Synchronized
    override fun onOrderingViolation(conversationId: String, messageId: String, detail: String, elapsedMs: Long) {
        record(elapsedMs, "ORDERING_VIOLATION conversationId=$conversationId messageId=$messageId detail=$detail")
    }

    @Synchronized
    override fun onSnapshotBuilt(conversationCount: Int, messageCount: Int, elapsedMs: Long) {
        record(elapsedMs, "SNAPSHOT_BUILT conversations=$conversationCount messages=$messageCount")
    }

    @Synchronized
    override fun onRestoreApplied(conversationCount: Int, messageCount: Int, elapsedMs: Long) {
        record(elapsedMs, "RESTORE_APPLIED conversations=$conversationCount messages=$messageCount")
    }

    @Synchronized
    override fun onRestoreVerification(conversationId: String, ok: Boolean, detail: String, elapsedMs: Long) {
        record(elapsedMs, "RESTORE_VERIFY conversationId=$conversationId ok=$ok detail=$detail")
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("ConversationMessageStore Diagnostics Report")
        sb.appendLine("entries=${entries.size}")
        sb.appendLine("format=t+<ms> <event>")
        for (e in entries) {
            sb.appendLine("t+${e.elapsedMs}ms ${e.message}")
        }
        return sb.toString().trimEnd()
    }

    @Synchronized
    override fun close() {
        entries.clear()
    }

    private fun record(elapsedMs: Long, message: String) {
        if (entries.size >= maxEntries) entries.removeAt(0)
        entries.add(Entry(elapsedMs = elapsedMs, message = message))
    }
}
