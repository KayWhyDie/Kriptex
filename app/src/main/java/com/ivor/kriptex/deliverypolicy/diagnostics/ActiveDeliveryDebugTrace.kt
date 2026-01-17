package com.ivor.kriptex.deliverypolicy.diagnostics

/**
 * Developer-only diagnostics for Active delivery modeling.
 *
 * Safety:
 * - No payloads
 * - No peer identifiers
 * - No keys
 * - References are limited to messageId + sessionId
 */
interface ActiveDeliveryDebugTrace : AutoCloseable {
    fun onAttempt(messageId: String, sessionId: String, elapsedMs: Long)

    fun onTransportInvoked(messageId: String, sessionId: String, elapsedMs: Long)

    fun onSessionCompleted(
        messageId: String,
        sessionId: String,
        outcome: String,
        durationMs: Long,
        elapsedMs: Long,
    )

    fun onDowngradeToPassive(openSessionsCount: Int, elapsedMs: Long)

    fun dumpDebugReport(): String
}

object NoOpActiveDeliveryDebugTrace : ActiveDeliveryDebugTrace {
    override fun onAttempt(messageId: String, sessionId: String, elapsedMs: Long) = Unit
    override fun onTransportInvoked(messageId: String, sessionId: String, elapsedMs: Long) = Unit
    override fun onSessionCompleted(messageId: String, sessionId: String, outcome: String, durationMs: Long, elapsedMs: Long) = Unit
    override fun onDowngradeToPassive(openSessionsCount: Int, elapsedMs: Long) = Unit
    override fun dumpDebugReport(): String = "ActiveDelivery diagnostics disabled."
    override fun close() = Unit
}

class DefaultActiveDeliveryDebugTrace(
    private val maxEntries: Int = 500,
) : ActiveDeliveryDebugTrace {

    private data class Entry(val elapsedMs: Long, val message: String)

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

    @Synchronized
    override fun onAttempt(messageId: String, sessionId: String, elapsedMs: Long) {
        record(elapsedMs, "ATTEMPT messageId=$messageId sessionId=$sessionId")
    }

    @Synchronized
    override fun onTransportInvoked(messageId: String, sessionId: String, elapsedMs: Long) {
        record(elapsedMs, "TRANSPORT messageId=$messageId sessionId=$sessionId")
    }

    @Synchronized
    override fun onSessionCompleted(
        messageId: String,
        sessionId: String,
        outcome: String,
        durationMs: Long,
        elapsedMs: Long,
    ) {
        record(elapsedMs, "SESSION_COMPLETE messageId=$messageId sessionId=$sessionId outcome=$outcome durationMs=$durationMs")
    }

    @Synchronized
    override fun onDowngradeToPassive(openSessionsCount: Int, elapsedMs: Long) {
        record(elapsedMs, "DOWNGRADE_TO_PASSIVE openSessions=$openSessionsCount")
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("ActiveDelivery Diagnostics Report")
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
