package com.ivor.kriptex.deliverypolicy.diagnostics

/**
 * Developer-only diagnostics for passive delivery buffering.
 *
 * Safety:
 * - No payloads
 * - No peer identifiers
 * - References are limited to messageId + sessionId
 */
interface PassiveDeliveryBufferDebugTrace : AutoCloseable {
    fun onAvailableChanged(isAvailable: Boolean, size: Int, elapsedMs: Long)

    fun onBuffered(messageId: String, sessionId: String, size: Int, elapsedMs: Long)

    fun onDrain(count: Int, sizeAfter: Int, elapsedMs: Long)

    fun onSessionResumePlanned(messageId: String, sessionId: String, elapsedMs: Long)

    fun dumpDebugReport(): String
}

object NoOpPassiveDeliveryBufferDebugTrace : PassiveDeliveryBufferDebugTrace {
    override fun onAvailableChanged(isAvailable: Boolean, size: Int, elapsedMs: Long) = Unit
    override fun onBuffered(messageId: String, sessionId: String, size: Int, elapsedMs: Long) = Unit
    override fun onDrain(count: Int, sizeAfter: Int, elapsedMs: Long) = Unit
    override fun onSessionResumePlanned(messageId: String, sessionId: String, elapsedMs: Long) = Unit
    override fun dumpDebugReport(): String = "PassiveDeliveryBuffer diagnostics disabled."
    override fun close() = Unit
}

class DefaultPassiveDeliveryBufferDebugTrace(
    private val maxEntries: Int = 500,
) : PassiveDeliveryBufferDebugTrace {

    private data class Entry(val elapsedMs: Long, val message: String)

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

    @Synchronized
    override fun onAvailableChanged(isAvailable: Boolean, size: Int, elapsedMs: Long) {
        record(elapsedMs, "AVAILABLE isAvailable=$isAvailable size=$size")
    }

    @Synchronized
    override fun onBuffered(messageId: String, sessionId: String, size: Int, elapsedMs: Long) {
        record(elapsedMs, "ENQUEUE messageId=$messageId sessionId=$sessionId size=$size")
    }

    @Synchronized
    override fun onDrain(count: Int, sizeAfter: Int, elapsedMs: Long) {
        record(elapsedMs, "DRAIN count=$count sizeAfter=$sizeAfter")
    }

    @Synchronized
    override fun onSessionResumePlanned(messageId: String, sessionId: String, elapsedMs: Long) {
        record(elapsedMs, "RESUME_PLANNED messageId=$messageId sessionId=$sessionId")
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("PassiveDeliveryBuffer Diagnostics Report")
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
