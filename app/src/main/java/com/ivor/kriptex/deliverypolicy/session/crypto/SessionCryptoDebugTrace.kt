package com.ivor.kriptex.deliverypolicy.session.crypto

import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm

interface SessionCryptoDebugTrace : AutoCloseable {

    fun onAlgorithmSelected(sessionId: String, algorithm: SessionAeadAlgorithm)

    fun onEncrypt(sessionId: String, messageId: String, algorithm: SessionAeadAlgorithm, plaintextBytes: Int, ciphertextBytes: Int)

    fun onDecrypt(sessionId: String, messageId: String, algorithm: SessionAeadAlgorithm, ciphertextBytes: Int, plaintextBytes: Int)

    fun onDecryptRejected(sessionId: String, messageId: String, reason: String)

    fun onRestoreVerification(sessionId: String, ok: Boolean, detail: String)

    fun dumpDebugReport(): String
}

object NoOpSessionCryptoDebugTrace : SessionCryptoDebugTrace {
    override fun onAlgorithmSelected(sessionId: String, algorithm: SessionAeadAlgorithm) = Unit
    override fun onEncrypt(sessionId: String, messageId: String, algorithm: SessionAeadAlgorithm, plaintextBytes: Int, ciphertextBytes: Int) = Unit
    override fun onDecrypt(sessionId: String, messageId: String, algorithm: SessionAeadAlgorithm, ciphertextBytes: Int, plaintextBytes: Int) = Unit
    override fun onDecryptRejected(sessionId: String, messageId: String, reason: String) = Unit
    override fun onRestoreVerification(sessionId: String, ok: Boolean, detail: String) = Unit
    override fun dumpDebugReport(): String = "Session crypto diagnostics disabled."
    override fun close() = Unit
}

class DefaultSessionCryptoDebugTrace(private val maxEntries: Int = 500) : SessionCryptoDebugTrace {

    private data class Entry(val msg: String)

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

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
    override fun dumpDebugReport(): String {
        return buildString {
            appendLine("Session Crypto Diagnostics Report")
            appendLine("entries=${entries.size}")
            entries.forEach { appendLine(it.msg) }
        }.trimEnd()
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
