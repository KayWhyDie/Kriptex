package com.ivor.kriptex.deliverypolicy.connection

import com.ivor.kriptex.deliverypolicy.ConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable, debounced view of peer availability.
 *
 * This provider does not perform networking, messaging, or transport decisions.
 * It derives a [ConnectionState] from caller-supplied signals and emits changes
 * only when meaningful (debounced/hysteresis).
 */
interface ConnectionStateProvider {
    /** Current debounced/stable state. */
    val state: ConnectionState

    /** Hot stream of debounced/stable state changes. */
    val stateFlow: StateFlow<ConnectionState>

    /**
     * Callback-based observation. The listener is immediately invoked with the current state.
     * Returns an unsubscribe function.
     */
    fun addListener(listener: (ConnectionState) -> Unit): () -> Unit
}
