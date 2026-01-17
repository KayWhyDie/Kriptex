package com.ivor.kriptex.deliverypolicy.diagnostics

import com.ivor.kriptex.deliverypolicy.inbound.AckDecision
import com.ivor.kriptex.deliverypolicy.inbound.InboundMessage

/**
 * Developer-only diagnostics for the inbound pipeline.
 *
 * Safety:
 * - No payloads
 * - No peer identifiers beyond opaque senderId (we do not log senderId)
 * - No keys
 */
interface InboundPipelineDebugTrace : AutoCloseable {
    fun onAccepted(messageId: String, conversationId: String, kind: InboundMessage.Kind, receivedAtElapsedMs: Long)

    fun onDeduplicated(messageId: String, conversationId: String, receivedAtElapsedMs: Long)

    fun onReceiveIndexAssigned(messageId: String, conversationId: String, receiveIndex: Int, elapsedMs: Long)

    fun onLedgerUpdated(messageId: String, update: String, elapsedMs: Long)

    fun onAckDecision(messageId: String, decision: AckDecision, elapsedMs: Long)

    fun onSnapshotBuilt(processedCount: Int, conversationCount: Int, pendingAckCount: Int, elapsedMs: Long)

    fun onRestoreApplied(processedCount: Int, conversationCount: Int, pendingAckCount: Int, elapsedMs: Long)

    fun dumpDebugReport(): String
}

object NoOpInboundPipelineDebugTrace : InboundPipelineDebugTrace {
    override fun onAccepted(messageId: String, conversationId: String, kind: InboundMessage.Kind, receivedAtElapsedMs: Long) = Unit
    override fun onDeduplicated(messageId: String, conversationId: String, receivedAtElapsedMs: Long) = Unit
    override fun onReceiveIndexAssigned(messageId: String, conversationId: String, receiveIndex: Int, elapsedMs: Long) = Unit
    override fun onLedgerUpdated(messageId: String, update: String, elapsedMs: Long) = Unit
    override fun onAckDecision(messageId: String, decision: AckDecision, elapsedMs: Long) = Unit
    override fun onSnapshotBuilt(processedCount: Int, conversationCount: Int, pendingAckCount: Int, elapsedMs: Long) = Unit
    override fun onRestoreApplied(processedCount: Int, conversationCount: Int, pendingAckCount: Int, elapsedMs: Long) = Unit
    override fun dumpDebugReport(): String = "Inbound pipeline diagnostics disabled."
    override fun close() = Unit
}

class DefaultInboundPipelineDebugTrace(
    private val maxEntries: Int = 500,
) : InboundPipelineDebugTrace {

    private data class Entry(val elapsedMs: Long, val message: String)

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

    @Synchronized
    override fun onAccepted(messageId: String, conversationId: String, kind: InboundMessage.Kind, receivedAtElapsedMs: Long) {
        record(receivedAtElapsedMs, "ACCEPTED conversationId=$conversationId kind=${kind.name} messageId=$messageId")
    }

    @Synchronized
    override fun onDeduplicated(messageId: String, conversationId: String, receivedAtElapsedMs: Long) {
        record(receivedAtElapsedMs, "DEDUP conversationId=$conversationId messageId=$messageId")
    }

    @Synchronized
    override fun onReceiveIndexAssigned(messageId: String, conversationId: String, receiveIndex: Int, elapsedMs: Long) {
        record(elapsedMs, "RECEIVE_INDEX conversationId=$conversationId index=$receiveIndex messageId=$messageId")
    }

    @Synchronized
    override fun onLedgerUpdated(messageId: String, update: String, elapsedMs: Long) {
        record(elapsedMs, "LEDGER messageId=$messageId update=$update")
    }

    @Synchronized
    override fun onAckDecision(messageId: String, decision: AckDecision, elapsedMs: Long) {
        record(elapsedMs, "ACK_DECISION decision=${decision.name} messageId=$messageId")
    }

    @Synchronized
    override fun onSnapshotBuilt(processedCount: Int, conversationCount: Int, pendingAckCount: Int, elapsedMs: Long) {
        record(elapsedMs, "SNAPSHOT_BUILT processed=$processedCount conversations=$conversationCount pendingAcks=$pendingAckCount")
    }

    @Synchronized
    override fun onRestoreApplied(processedCount: Int, conversationCount: Int, pendingAckCount: Int, elapsedMs: Long) {
        record(elapsedMs, "RESTORE_APPLIED processed=$processedCount conversations=$conversationCount pendingAcks=$pendingAckCount")
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("InboundPipeline Diagnostics Report")
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
