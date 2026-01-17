package com.ivor.kriptex.deliverypolicy.diagnostics

/**
 * Developer-only diagnostics for session-based outbox delivery.
 *
 * Safety:
 * - No payloads
 * - No peer identifiers
 * - References are limited to messageId + sessionId
 */
interface MessageOutboxSessionDebugTrace : AutoCloseable {
    fun onSessionCreated(messageId: String, sessionId: String, elapsedMs: Long)

    fun onSessionCompleted(messageId: String, sessionId: String, outcome: String, durationMs: Long, elapsedMs: Long)

    fun dumpDebugReport(): String
}

object NoOpMessageOutboxSessionDebugTrace : MessageOutboxSessionDebugTrace {
    override fun onSessionCreated(messageId: String, sessionId: String, elapsedMs: Long) = Unit
    override fun onSessionCompleted(messageId: String, sessionId: String, outcome: String, durationMs: Long, elapsedMs: Long) = Unit
    override fun dumpDebugReport(): String = "MessageOutbox session diagnostics disabled."
    override fun close() = Unit
}

class DefaultMessageOutboxSessionDebugTrace(
    private val maxEntries: Int = 500,
) : MessageOutboxSessionDebugTrace {

    private data class Entry(val elapsedMs: Long, val message: String)

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

    @Synchronized
    override fun onSessionCreated(messageId: String, sessionId: String, elapsedMs: Long) {
        record(elapsedMs, "SESSION_CREATE messageId=$messageId sessionId=$sessionId")
    }

    @Synchronized
    override fun onSessionCompleted(messageId: String, sessionId: String, outcome: String, durationMs: Long, elapsedMs: Long) {
        record(elapsedMs, "SESSION_COMPLETE messageId=$messageId sessionId=$sessionId outcome=$outcome durationMs=$durationMs")
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("MessageOutbox Session Diagnostics Report")
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
