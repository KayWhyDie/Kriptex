package com.ivor.kriptex.deliverypolicy.adversarial

import com.ivor.kriptex.deliverypolicy.conversationattention.NotificationSink
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.session.crypto.SessionCryptoDebugTrace

class RecordingSessionCryptoDebugTrace(private val maxEntries: Int = 500) : SessionCryptoDebugTrace {

    private val entries = ArrayDeque<String>(minOf(maxEntries, 32))
    private val decryptedMessageIds = LinkedHashSet<String>()

    @Synchronized
    override fun onAlgorithmSelected(sessionId: String, algorithm: SessionAeadAlgorithm) {
        record("ALGO sessionId=$sessionId algorithm=${algorithm.name}")
    }

    @Synchronized
    override fun onEncrypt(sessionId: String, messageId: String, algorithm: SessionAeadAlgorithm, plaintextBytes: Int, ciphertextBytes: Int) {
        record("ENCRYPT sessionId=$sessionId messageId=$messageId algorithm=${algorithm.name} plaintextBytes=$plaintextBytes ciphertextBytes=$ciphertextBytes")
    }

    @Synchronized
    override fun onDecrypt(sessionId: String, messageId: String, algorithm: SessionAeadAlgorithm, ciphertextBytes: Int, plaintextBytes: Int) {
        decryptedMessageIds.add(messageId)
        record("DECRYPT sessionId=$sessionId messageId=$messageId algorithm=${algorithm.name} ciphertextBytes=$ciphertextBytes plaintextBytes=$plaintextBytes")
    }

    @Synchronized
    override fun onDecryptRejected(sessionId: String, messageId: String, reason: String) {
        record("REJECT sessionId=$sessionId messageId=$messageId reason=$reason")
    }

    @Synchronized
    override fun onRestoreVerification(sessionId: String, ok: Boolean, detail: String) {
        record("RESTORE_VERIFY sessionId=$sessionId ok=$ok detail=$detail")
    }

    @Synchronized
    fun decryptedMessageIdsSnapshot(): Set<String> = decryptedMessageIds.toSet()

    @Synchronized
    override fun dumpDebugReport(): String {
        return buildString {
            appendLine("Session Crypto Diagnostics Report")
            appendLine("entries=${entries.size} decryptedIds=${decryptedMessageIds.size}")
            entries.forEach { appendLine(it) }
        }.trimEnd()
    }

    @Synchronized
    override fun close() {
        entries.clear()
        decryptedMessageIds.clear()
    }

    @Synchronized
    private fun record(msg: String) {
        if (entries.size >= maxEntries) entries.removeFirst()
        entries.addLast(msg)
    }
}

class RecordingNotificationSink(private val who: ConversationScenario.Actor) : NotificationSink {

    enum class Kind { SHOW, CANCEL }

    data class Event(
        val kind: Kind,
        val stepLabel: String,
        val conversationId: String,
        val lastActivityTimestamp: Long? = null,
    )

    @Volatile
    var stepLabelProvider: () -> String = { "(unknown_step)" }

    private val events = ArrayDeque<Event>(64)

    @Synchronized
    override fun showNotification(conversationId: String, snapshot: ConversationSnapshot) {
        record(Kind.SHOW, conversationId, snapshot.lastActivityTimestamp)
    }

    @Synchronized
    override fun cancelNotification(conversationId: String) {
        record(Kind.CANCEL, conversationId, null)
    }

    @Synchronized
    fun drainEventsForStep(stepLabel: String): List<Event> {
        // Keep a bounded log globally but return only the events emitted during this step.
        return events.filter { it.stepLabel == stepLabel }
    }

    @Synchronized
    private fun record(kind: Kind, conversationId: String, lastActivityTimestamp: Long?) {
        if (events.size >= 64) events.removeFirst()
        events.addLast(
            Event(
                kind = kind,
                stepLabel = stepLabelProvider.invoke(),
                conversationId = conversationId,
                lastActivityTimestamp = lastActivityTimestamp,
            ),
        )
    }

    override fun toString(): String = "RecordingNotificationSink(who=$who events=${events.size})"
}
