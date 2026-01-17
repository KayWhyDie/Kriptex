package com.ivor.kriptex.deliverypolicy.diagnostics

import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage

interface ProtocolDebugTrace : AutoCloseable {

    fun onEncode(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long, sizeBytes: Int)

    fun onDecode(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long, sizeBytes: Int)

    fun onInboundAccepted(messageId: String, conversationId: String, type: ProtocolMessage.Type, receiveIndex: Int, elapsedMs: Long)

    fun onInboundDeduplicated(messageId: String, conversationId: String, elapsedMs: Long)

    fun onOutboundPending(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long)

    fun onOutboundEnqueued(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long)

    fun onSnapshotBuilt(processedCount: Int, pendingOutboundCount: Int, elapsedMs: Long)

    fun onRestoreApplied(processedCount: Int, pendingOutboundCount: Int, elapsedMs: Long)

    fun dumpDebugReport(): String
}

object NoOpProtocolDebugTrace : ProtocolDebugTrace {
    override fun onEncode(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long, sizeBytes: Int) = Unit
    override fun onDecode(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long, sizeBytes: Int) = Unit
    override fun onInboundAccepted(messageId: String, conversationId: String, type: ProtocolMessage.Type, receiveIndex: Int, elapsedMs: Long) = Unit
    override fun onInboundDeduplicated(messageId: String, conversationId: String, elapsedMs: Long) = Unit
    override fun onOutboundPending(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long) = Unit
    override fun onOutboundEnqueued(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long) = Unit
    override fun onSnapshotBuilt(processedCount: Int, pendingOutboundCount: Int, elapsedMs: Long) = Unit
    override fun onRestoreApplied(processedCount: Int, pendingOutboundCount: Int, elapsedMs: Long) = Unit
    override fun dumpDebugReport(): String = "Protocol diagnostics disabled."
    override fun close() = Unit
}

class DefaultProtocolDebugTrace(private val maxEntries: Int = 500) : ProtocolDebugTrace {

    private data class Entry(val elapsedMs: Long, val msg: String)

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

    @Synchronized
    override fun onEncode(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long, sizeBytes: Int) {
        record(elapsedMs, "ENCODE type=${type.name} conversationId=$conversationId messageId=$messageId sizeBytes=$sizeBytes")
    }

    @Synchronized
    override fun onDecode(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long, sizeBytes: Int) {
        record(elapsedMs, "DECODE type=${type.name} conversationId=$conversationId messageId=$messageId sizeBytes=$sizeBytes")
    }

    @Synchronized
    override fun onInboundAccepted(messageId: String, conversationId: String, type: ProtocolMessage.Type, receiveIndex: Int, elapsedMs: Long) {
        record(elapsedMs, "INBOUND_ACCEPT type=${type.name} conversationId=$conversationId messageId=$messageId receiveIndex=$receiveIndex")
    }

    @Synchronized
    override fun onInboundDeduplicated(messageId: String, conversationId: String, elapsedMs: Long) {
        record(elapsedMs, "INBOUND_DEDUP conversationId=$conversationId messageId=$messageId")
    }

    @Synchronized
    override fun onOutboundPending(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long) {
        record(elapsedMs, "OUTBOUND_PENDING type=${type.name} conversationId=$conversationId messageId=$messageId")
    }

    @Synchronized
    override fun onOutboundEnqueued(messageId: String, conversationId: String, type: ProtocolMessage.Type, elapsedMs: Long) {
        record(elapsedMs, "OUTBOUND_ENQUEUED type=${type.name} conversationId=$conversationId messageId=$messageId")
    }

    @Synchronized
    override fun onSnapshotBuilt(processedCount: Int, pendingOutboundCount: Int, elapsedMs: Long) {
        record(elapsedMs, "SNAPSHOT_BUILT processed=$processedCount pendingOutbound=$pendingOutboundCount")
    }

    @Synchronized
    override fun onRestoreApplied(processedCount: Int, pendingOutboundCount: Int, elapsedMs: Long) {
        record(elapsedMs, "RESTORE_APPLIED processed=$processedCount pendingOutbound=$pendingOutboundCount")
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("Protocol Diagnostics Report")
        sb.appendLine("entries=${entries.size}")
        for (e in entries) {
            sb.appendLine("t+${e.elapsedMs}ms ${e.msg}")
        }
        return sb.toString().trimEnd()
    }

    @Synchronized
    override fun close() {
        entries.clear()
    }

    private fun record(elapsedMs: Long, msg: String) {
        if (entries.size >= maxEntries) entries.removeAt(0)
        entries.add(Entry(elapsedMs, msg))
    }
}
