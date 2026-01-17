package com.ivor.kriptex.deliverypolicy.diagnostics

import com.ivor.kriptex.deliverypolicy.DeliveryMode
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy

/**
 * Developer-only diagnostics for the message outbox.
 *
 * Safety:
 * - No payloads
 * - No peer identifiers
 * - Message references are limited to messageId
 */
interface MessageOutboxDebugTrace : AutoCloseable {
    fun onEnqueued(messageId: String, elapsedMs: Long)

    fun onStrategyChanged(prev: DeliveryStrategy, next: DeliveryStrategy, elapsedMs: Long)

    fun onAttempt(messageId: String, strategy: DeliveryStrategy, reason: String, elapsedMs: Long)

    fun onAttemptResult(messageId: String, result: String, detail: String? = null, elapsedMs: Long)

    fun onDelivered(messageId: String, elapsedMs: Long)

    fun onFailed(messageId: String, retryable: Boolean, reason: String? = null, elapsedMs: Long)

    fun dumpDebugReport(): String
}

object NoOpMessageOutboxDebugTrace : MessageOutboxDebugTrace {
    override fun onEnqueued(messageId: String, elapsedMs: Long) = Unit
    override fun onStrategyChanged(prev: DeliveryStrategy, next: DeliveryStrategy, elapsedMs: Long) = Unit
    override fun onAttempt(messageId: String, strategy: DeliveryStrategy, reason: String, elapsedMs: Long) = Unit
    override fun onAttemptResult(messageId: String, result: String, detail: String?, elapsedMs: Long) = Unit
    override fun onDelivered(messageId: String, elapsedMs: Long) = Unit
    override fun onFailed(messageId: String, retryable: Boolean, reason: String?, elapsedMs: Long) = Unit
    override fun dumpDebugReport(): String = "MessageOutbox diagnostics disabled."
    override fun close() = Unit
}

class DefaultMessageOutboxDebugTrace(
    private val maxEntries: Int = 500,
) : MessageOutboxDebugTrace {

    private data class Entry(
        val elapsedMs: Long,
        val message: String,
    )

    private val entries = ArrayList<Entry>(minOf(maxEntries, 32))

    @Synchronized
    override fun onEnqueued(messageId: String, elapsedMs: Long) {
        record(elapsedMs, "ENQUEUE messageId=$messageId")
    }

    @Synchronized
    override fun onStrategyChanged(prev: DeliveryStrategy, next: DeliveryStrategy, elapsedMs: Long) {
        record(
            elapsedMs,
            "STRATEGY_CHANGE ${formatMode(prev)} -> ${formatMode(next)}",
        )
    }

    @Synchronized
    override fun onAttempt(messageId: String, strategy: DeliveryStrategy, reason: String, elapsedMs: Long) {
        record(
            elapsedMs,
            "ATTEMPT messageId=$messageId mode=${formatMode(strategy)} reason=$reason",
        )
    }

    @Synchronized
    override fun onAttemptResult(messageId: String, result: String, detail: String?, elapsedMs: Long) {
        val suffix = if (detail.isNullOrBlank()) "" else " detail=${sanitize(detail)}"
        record(elapsedMs, "RESULT messageId=$messageId $result$suffix")
    }

    @Synchronized
    override fun onDelivered(messageId: String, elapsedMs: Long) {
        record(elapsedMs, "DELIVERED messageId=$messageId")
    }

    @Synchronized
    override fun onFailed(messageId: String, retryable: Boolean, reason: String?, elapsedMs: Long) {
        val suffix = if (reason.isNullOrBlank()) "" else " reason=${sanitize(reason)}"
        record(elapsedMs, "FAILED messageId=$messageId retryable=$retryable$suffix")
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("MessageOutbox Diagnostics Report")
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

    private fun formatMode(strategy: DeliveryStrategy): String {
        return when (strategy.mode) {
            DeliveryMode.ACTIVE -> "ACTIVE"
            DeliveryMode.PASSIVE -> "PASSIVE"
        }
    }

    private fun sanitize(input: String): String {
        // Keep output structured but prevent multi-line injection.
        return input.replace("\n", " ").replace("\r", " ").trim()
    }

    private fun record(elapsedMs: Long, msg: String) {
        if (entries.size >= maxEntries) entries.removeAt(0)
        entries.add(Entry(elapsedMs = elapsedMs, message = msg))
    }
}
