package com.ivor.kriptex.deliverypolicy.diagnostics

import com.ivor.kriptex.deliverypolicy.persistence.PersistedMessageOutboxSnapshot

/**
 * Developer-only diagnostics for persistence boundary behavior.
 *
 * Safety:
 * - No payloads
 * - No peer identifiers
 * - No keys
 */
interface MessageOutboxPersistenceDebugTrace : AutoCloseable {
    fun onSnapshotBuilt(messageCount: Int, capturedAtElapsedMs: Long)

    fun onRestoreApplied(messageCount: Int, capturedAtElapsedMs: Long)

    fun onRestoreDeduplicated(droppedCount: Int, capturedAtElapsedMs: Long)

    fun dumpDebugReport(): String
}

object NoOpMessageOutboxPersistenceDebugTrace : MessageOutboxPersistenceDebugTrace {
    override fun onSnapshotBuilt(messageCount: Int, capturedAtElapsedMs: Long) = Unit
    override fun onRestoreApplied(messageCount: Int, capturedAtElapsedMs: Long) = Unit
    override fun onRestoreDeduplicated(droppedCount: Int, capturedAtElapsedMs: Long) = Unit
    override fun dumpDebugReport(): String = "MessageOutbox persistence diagnostics disabled."
    override fun close() = Unit
}

class DefaultMessageOutboxPersistenceDebugTrace(
    private val maxEntries: Int = 200,
) : MessageOutboxPersistenceDebugTrace {

    private data class Entry(val elapsedMs: Long, val message: String)

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

    @Synchronized
    override fun onSnapshotBuilt(messageCount: Int, capturedAtElapsedMs: Long) {
        record(capturedAtElapsedMs, "SNAPSHOT_BUILT messages=$messageCount")
    }

    @Synchronized
    override fun onRestoreApplied(messageCount: Int, capturedAtElapsedMs: Long) {
        record(capturedAtElapsedMs, "RESTORE_APPLIED messages=$messageCount")
    }

    @Synchronized
    override fun onRestoreDeduplicated(droppedCount: Int, capturedAtElapsedMs: Long) {
        record(capturedAtElapsedMs, "RESTORE_DEDUP dropped=$droppedCount")
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("MessageOutbox Persistence Diagnostics Report")
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

fun PersistedMessageOutboxSnapshot.redactedSummary(): String {
    // Intentionally does not include payload bytes.
    return "PersistedMessageOutboxSnapshot(version=$version messages=${messages.size} capturedAtElapsedMs=$capturedAtElapsedMs)"
}
