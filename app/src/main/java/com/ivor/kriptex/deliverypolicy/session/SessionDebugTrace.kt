package com.ivor.kriptex.deliverypolicy.session

interface SessionDebugTrace : AutoCloseable {

    fun onSessionInitCreated(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long)

    fun onSessionInitAccepted(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long)

    fun onSessionAcceptReceived(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long)

    fun onSessionEstablished(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long)

    fun onMessageRejected(peerId: String, reason: String, elapsedMs: Long)

    fun onReplayDetected(peerId: String, sessionId: String, seq: Long, elapsedMs: Long)

    fun onSnapshotBuilt(sessionCount: Int, elapsedMs: Long)

    fun onRestoreApplied(sessionCount: Int, elapsedMs: Long)

    fun onRestoreVerification(peerId: String, sessionId: String, ok: Boolean, detail: String, elapsedMs: Long)

    fun dumpDebugReport(): String
}

object NoOpSessionDebugTrace : SessionDebugTrace {
    override fun onSessionInitCreated(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long) = Unit
    override fun onSessionInitAccepted(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long) = Unit
    override fun onSessionAcceptReceived(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long) = Unit
    override fun onSessionEstablished(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long) = Unit
    override fun onMessageRejected(peerId: String, reason: String, elapsedMs: Long) = Unit
    override fun onReplayDetected(peerId: String, sessionId: String, seq: Long, elapsedMs: Long) = Unit
    override fun onSnapshotBuilt(sessionCount: Int, elapsedMs: Long) = Unit
    override fun onRestoreApplied(sessionCount: Int, elapsedMs: Long) = Unit
    override fun onRestoreVerification(peerId: String, sessionId: String, ok: Boolean, detail: String, elapsedMs: Long) = Unit
    override fun dumpDebugReport(): String = "Session diagnostics disabled."
    override fun close() = Unit
}

class DefaultSessionDebugTrace(private val maxEntries: Int = 500) : SessionDebugTrace {

    private data class Entry(val elapsedMs: Long, val msg: String)

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

    @Synchronized
    override fun onSessionInitCreated(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long) {
        record(elapsedMs, "SESSION_INIT_CREATED peerId=$peerId conversationId=$conversationId sessionId=$sessionId")
    }

    @Synchronized
    override fun onSessionInitAccepted(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long) {
        record(elapsedMs, "SESSION_INIT_ACCEPTED peerId=$peerId conversationId=$conversationId sessionId=$sessionId")
    }

    @Synchronized
    override fun onSessionAcceptReceived(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long) {
        record(elapsedMs, "SESSION_ACCEPT_RECEIVED peerId=$peerId conversationId=$conversationId sessionId=$sessionId")
    }

    @Synchronized
    override fun onSessionEstablished(peerId: String, conversationId: String, sessionId: String, elapsedMs: Long) {
        record(elapsedMs, "SESSION_ESTABLISHED peerId=$peerId conversationId=$conversationId sessionId=$sessionId")
    }

    @Synchronized
    override fun onMessageRejected(peerId: String, reason: String, elapsedMs: Long) {
        record(elapsedMs, "REJECT peerId=$peerId reason=$reason")
    }

    @Synchronized
    override fun onReplayDetected(peerId: String, sessionId: String, seq: Long, elapsedMs: Long) {
        record(elapsedMs, "REPLAY peerId=$peerId sessionId=$sessionId seq=$seq")
    }

    @Synchronized
    override fun onSnapshotBuilt(sessionCount: Int, elapsedMs: Long) {
        record(elapsedMs, "SNAPSHOT_BUILT sessions=$sessionCount")
    }

    @Synchronized
    override fun onRestoreApplied(sessionCount: Int, elapsedMs: Long) {
        record(elapsedMs, "RESTORE_APPLIED sessions=$sessionCount")
    }

    @Synchronized
    override fun onRestoreVerification(peerId: String, sessionId: String, ok: Boolean, detail: String, elapsedMs: Long) {
        record(elapsedMs, "RESTORE_VERIFY peerId=$peerId sessionId=$sessionId ok=$ok detail=$detail")
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("Session Diagnostics Report")
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
