package com.ivor.kriptex.deliverypolicy.diagnostics

/**
 * Developer-only diagnostics for the conversation-level delivery ledger.
 *
 * Safety:
 * - No payloads
 * - No peer identifiers
 * - No keys
 */
interface ConversationDeliveryLedgerDebugTrace : AutoCloseable {
    fun onEnqueued(messageId: String, conversationId: String, index: Int, elapsedMs: Long)

    fun onTransition(messageId: String, from: String, to: String, elapsedMs: Long)

    /** Records that an ACK was applied but did not advance the contiguous ACK cursor. */
    fun onOutOfOrderAck(messageId: String, conversationId: String, index: Int, ackedPrefixCount: Int, elapsedMs: Long)

    fun onIdempotentEvent(messageId: String, event: String, elapsedMs: Long)

    fun onSnapshotBuilt(messageCount: Int, conversationCount: Int, elapsedMs: Long)

    fun onRestoreApplied(messageCount: Int, conversationCount: Int, elapsedMs: Long)

    fun dumpDebugReport(): String
}

object NoOpConversationDeliveryLedgerDebugTrace : ConversationDeliveryLedgerDebugTrace {
    override fun onEnqueued(messageId: String, conversationId: String, index: Int, elapsedMs: Long) = Unit
    override fun onTransition(messageId: String, from: String, to: String, elapsedMs: Long) = Unit
    override fun onOutOfOrderAck(messageId: String, conversationId: String, index: Int, ackedPrefixCount: Int, elapsedMs: Long) = Unit
    override fun onIdempotentEvent(messageId: String, event: String, elapsedMs: Long) = Unit
    override fun onSnapshotBuilt(messageCount: Int, conversationCount: Int, elapsedMs: Long) = Unit
    override fun onRestoreApplied(messageCount: Int, conversationCount: Int, elapsedMs: Long) = Unit
    override fun dumpDebugReport(): String = "ConversationDeliveryLedger diagnostics disabled."
    override fun close() = Unit
}

class DefaultConversationDeliveryLedgerDebugTrace(
    private val maxEntries: Int = 500,
) : ConversationDeliveryLedgerDebugTrace {

    private data class Entry(val elapsedMs: Long, val message: String)

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

    @Synchronized
    override fun onEnqueued(messageId: String, conversationId: String, index: Int, elapsedMs: Long) {
        record(elapsedMs, "ENQUEUE conversationId=$conversationId index=$index messageId=$messageId")
    }

    @Synchronized
    override fun onTransition(messageId: String, from: String, to: String, elapsedMs: Long) {
        record(elapsedMs, "TRANSITION messageId=$messageId from=$from to=$to")
    }

    @Synchronized
    override fun onOutOfOrderAck(messageId: String, conversationId: String, index: Int, ackedPrefixCount: Int, elapsedMs: Long) {
        record(elapsedMs, "OUT_OF_ORDER_ACK conversationId=$conversationId index=$index ackedPrefix=$ackedPrefixCount messageId=$messageId")
    }

    @Synchronized
    override fun onIdempotentEvent(messageId: String, event: String, elapsedMs: Long) {
        record(elapsedMs, "IDEMPOTENT event=$event messageId=$messageId")
    }

    @Synchronized
    override fun onSnapshotBuilt(messageCount: Int, conversationCount: Int, elapsedMs: Long) {
        record(elapsedMs, "SNAPSHOT_BUILT conversations=$conversationCount messages=$messageCount")
    }

    @Synchronized
    override fun onRestoreApplied(messageCount: Int, conversationCount: Int, elapsedMs: Long) {
        record(elapsedMs, "RESTORE_APPLIED conversations=$conversationCount messages=$messageCount")
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("ConversationDeliveryLedger Diagnostics Report")
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
