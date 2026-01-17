package com.ivor.kriptex.deliverypolicy.decision

import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import kotlinx.coroutines.flow.StateFlow

/**
 * Observes derived connection state and emits the currently active [DeliveryStrategy].
 *
 * One-way dependency:
 * ConnectionStateProvider -> DeliveryDecisionEngine -> DeliveryStrategy
 *
 * This engine performs no networking/messaging and has no side effects beyond emitting strategy.
 */
interface DeliveryDecisionEngine {
    /** Current strategy (may incorporate upgrade/downgrade hysteresis). */
    val strategy: DeliveryStrategy

    /** Hot stream of strategy changes. */
    val strategyFlow: StateFlow<DeliveryStrategy>

    /**
     * Callback-based observation. The listener is immediately invoked with current strategy.
     * Returns an unsubscribe function.
     */
    fun addListener(listener: (DeliveryStrategy) -> Unit): () -> Unit

    /** Stop observing upstream providers. */
    fun close()
}
