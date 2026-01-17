package com.ivor.kriptex.deliverypolicy.active

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.decision.DeliveryDecisionEngine
import com.ivor.kriptex.deliverypolicy.diagnostics.ActiveDeliveryDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpActiveDeliveryDebugTrace

/**
 * Observes strategy changes and enforces downgrade semantics:
 * when switching to Passive, any open active sessions are completed as deferred.
 */
class ActiveDeliveryDowngradeHandler(
    private val decisionEngine: DeliveryDecisionEngine,
    private val registry: OpenSessionRegistry,
    private val clock: Clock,
    private val debugTrace: ActiveDeliveryDebugTrace = NoOpActiveDeliveryDebugTrace,
    private val deferredReason: String = "downgraded_to_passive",
) : AutoCloseable {

    private var closed = false

    private val unsubscribe = decisionEngine.addListener { strategy ->
        if (closed) return@addListener
        if (strategy is PassiveDelivery) {
            debugTrace.onDowngradeToPassive(openSessionsCount = registry.size, elapsedMs = clock.nowMs())
            registry.completeAllDeferred(deferredReason)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        unsubscribe()
    }
}
