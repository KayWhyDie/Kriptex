package com.ivor.kriptex.deliverypolicy.session.ratchet

interface RatchetDebugTrace : AutoCloseable {

    fun onDhRatchetStep(sessionId: String, detail: String)

    fun onSymmetricRatchetStep(sessionId: String, direction: String, n: Int)

    fun onSkippedKeyStored(sessionId: String, count: Int)

    fun onSkippedKeyUsed(sessionId: String)

    fun onReplayRejected(sessionId: String, reason: String)

    fun onRestoreApplied(sessionCount: Int)

    fun dumpDebugReport(): String
}

object NoOpRatchetDebugTrace : RatchetDebugTrace {
    override fun onDhRatchetStep(sessionId: String, detail: String) = Unit
    override fun onSymmetricRatchetStep(sessionId: String, direction: String, n: Int) = Unit
    override fun onSkippedKeyStored(sessionId: String, count: Int) = Unit
    override fun onSkippedKeyUsed(sessionId: String) = Unit
    override fun onReplayRejected(sessionId: String, reason: String) = Unit
    override fun onRestoreApplied(sessionCount: Int) = Unit
    override fun dumpDebugReport(): String = "Ratchet diagnostics disabled."
    override fun close() = Unit
}

class DefaultRatchetDebugTrace(private val maxEntries: Int = 500) : RatchetDebugTrace {

    private data class Entry(val msg: String)

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

    @Synchronized
    override fun onDhRatchetStep(sessionId: String, detail: String) {
        record("DH_RATCHET sessionId=$sessionId $detail")
    }

    @Synchronized
    override fun onSymmetricRatchetStep(sessionId: String, direction: String, n: Int) {
        record("SYM_RATCHET sessionId=$sessionId dir=$direction n=$n")
    }

    @Synchronized
    override fun onSkippedKeyStored(sessionId: String, count: Int) {
        record("SKIPPED_STORE sessionId=$sessionId count=$count")
    }

    @Synchronized
    override fun onSkippedKeyUsed(sessionId: String) {
        record("SKIPPED_USE sessionId=$sessionId")
    }

    @Synchronized
    override fun onReplayRejected(sessionId: String, reason: String) {
        record("REPLAY_REJECT sessionId=$sessionId reason=$reason")
    }

    @Synchronized
    override fun onRestoreApplied(sessionCount: Int) {
        record("RESTORE_APPLIED sessions=$sessionCount")
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("Ratchet Diagnostics Report")
        sb.appendLine("entries=${entries.size}")
        entries.forEach { sb.appendLine(it.msg) }
        return sb.toString().trimEnd()
    }

    @Synchronized
    override fun close() {
        entries.clear()
    }

    private fun record(msg: String) {
        if (entries.size >= maxEntries) entries.removeAt(0)
        entries.add(Entry(msg))
    }
}
