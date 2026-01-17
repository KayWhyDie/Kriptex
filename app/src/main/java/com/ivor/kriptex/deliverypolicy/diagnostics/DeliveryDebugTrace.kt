package com.ivor.kriptex.deliverypolicy.diagnostics

import com.ivor.kriptex.deliverypolicy.ActiveDelivery
import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.ConnectionState
import com.ivor.kriptex.deliverypolicy.DeliveryMode
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.connection.ConnectionStateProvider
import com.ivor.kriptex.deliverypolicy.decision.DeliveryDecisionEngine

/**
 * Developer diagnostics for delivery policy plumbing.
 *
 * Observes (via callbacks):
 * - [ConnectionStateProvider] stable connection state transitions
 * - [DeliveryDecisionEngine] resulting delivery strategy decisions
 *
 * Records a bounded in-memory trace with monotonic relative timestamps and can produce a
 * human-readable report via [dumpDebugReport].
 *
 * Safety:
 * - No peer identifiers
 * - No payloads/keys
 * - No logging dependencies
 * - No networking / no threading
 *
 * Optional usage:
 * - Instantiate only in debug builds, or inject [NoOpDeliveryDebugTrace] in release.
 */
interface DeliveryDebugTrace : AutoCloseable {
    fun dumpDebugReport(): String
}

object NoOpDeliveryDebugTrace : DeliveryDebugTrace {
    override fun dumpDebugReport(): String = "Delivery diagnostics disabled."
    override fun close() = Unit
}

class DefaultDeliveryDebugTrace(
    private val connectionStateProvider: ConnectionStateProvider,
    private val decisionEngine: DeliveryDecisionEngine,
    private val clock: Clock,
    private val maxEntries: Int = 200,
) : DeliveryDebugTrace {

    private data class TraceEntry(
        val elapsedMs: Long,
        val kind: Kind,
        val message: String,
    ) {
        enum class Kind { INIT, CONNECTION, STRATEGY }
    }

    private val startedAtMs: Long = clock.nowMs()
    private val entries = ArrayList<TraceEntry>(minOf(maxEntries, 32))

    private var lastConnectionState: ConnectionState? = null
    private var lastStrategy: DeliveryStrategy? = null

    private val unsubscribeConnection = connectionStateProvider.addListener { onConnectionState(it) }
    private val unsubscribeStrategy = decisionEngine.addListener { onStrategy(it) }

    init {
        // Seed initial snapshots (without forcing any upstream behavior).
        record(
            TraceEntry.Kind.INIT,
            "connection=${formatConnection(connectionStateProvider.state)}, strategy=${formatStrategy(decisionEngine.strategy)}",
        )
        lastConnectionState = connectionStateProvider.state
        lastStrategy = decisionEngine.strategy
    }

    @Synchronized
    override fun dumpDebugReport(): String {
        val sb = StringBuilder()
        sb.appendLine("Delivery Diagnostics Report")
        sb.appendLine("entries=${entries.size}")
        sb.appendLine("format=t+<ms> <KIND> <details>")
        for (e in entries) {
            sb.appendLine("t+${e.elapsedMs}ms ${e.kind.name} ${e.message}")
        }
        return sb.toString().trimEnd()
    }

    @Synchronized
    override fun close() {
        unsubscribeConnection()
        unsubscribeStrategy()
        entries.clear()
    }

    @Synchronized
    private fun onConnectionState(newState: ConnectionState) {
        val prev = lastConnectionState
        if (prev == null) {
            lastConnectionState = newState
            record(TraceEntry.Kind.CONNECTION, "${formatConnection(newState)}")
            return
        }
        if (prev == newState) return
        lastConnectionState = newState
        record(
            TraceEntry.Kind.CONNECTION,
            "${formatConnection(prev)} -> ${formatConnection(newState)}",
        )
    }

    @Synchronized
    private fun onStrategy(newStrategy: DeliveryStrategy) {
        val prev = lastStrategy
        if (prev == null) {
            lastStrategy = newStrategy
            record(TraceEntry.Kind.STRATEGY, "${formatStrategy(newStrategy)}")
            return
        }
        if (prev == newStrategy) return
        val event = classifyStrategyChange(prev, newStrategy)
        lastStrategy = newStrategy
        record(
            TraceEntry.Kind.STRATEGY,
            "${formatStrategy(prev)} -> ${formatStrategy(newStrategy)} [$event]",
        )
    }

    private fun classifyStrategyChange(prev: DeliveryStrategy, next: DeliveryStrategy): String {
        if (prev.mode == DeliveryMode.PASSIVE && next.mode == DeliveryMode.ACTIVE) return "UPGRADE"
        if (prev.mode == DeliveryMode.ACTIVE && next.mode == DeliveryMode.PASSIVE) return "DOWNGRADE"
        return "CHANGE"
    }

    private fun formatConnection(state: ConnectionState): String {
        return when (state) {
            ConnectionState.Unknown -> "UNKNOWN"
            ConnectionState.DirectReady -> "DIRECT_READY"
            ConnectionState.DirectConnecting -> "DIRECT_CONNECTING"
            ConnectionState.RelayReady -> "RELAY_READY"
            ConnectionState.PeerOffline -> "PEER_OFFLINE"
        }
    }

    private fun formatStrategy(strategy: DeliveryStrategy): String {
        return when (strategy) {
            is ActiveDelivery -> "ACTIVE"
            is PassiveDelivery -> "PASSIVE(reason=${strategy.queueReason.name})"
        }
    }

    private fun record(kind: TraceEntry.Kind, message: String) {
        val elapsed = clock.nowMs() - startedAtMs
        if (entries.size >= maxEntries) {
            // Drop oldest to avoid unbounded growth.
            entries.removeAt(0)
        }

        val newEntry = TraceEntry(elapsedMs = elapsed, kind = kind, message = message)

        // Keep reports readable and deterministic: if a STRATEGY change is recorded before the
        // corresponding CONNECTION change within the same "tick", place the CONNECTION entry
        // immediately before that last STRATEGY entry (but do not reorder earlier events).
        if (
            kind == TraceEntry.Kind.CONNECTION &&
            entries.isNotEmpty() &&
            entries.last().elapsedMs == elapsed &&
            entries.last().kind == TraceEntry.Kind.STRATEGY
        ) {
            entries.add(entries.size - 1, newEntry)
            return
        }

        entries.add(newEntry)
    }
}
