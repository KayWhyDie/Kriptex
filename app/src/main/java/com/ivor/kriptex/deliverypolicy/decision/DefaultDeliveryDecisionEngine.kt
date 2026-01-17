package com.ivor.kriptex.deliverypolicy.decision

import com.ivor.kriptex.deliverypolicy.ActiveDelivery
import com.ivor.kriptex.deliverypolicy.ConnectionState
import com.ivor.kriptex.deliverypolicy.DefaultDeliveryPolicy
import com.ivor.kriptex.deliverypolicy.DeliveryPolicy
import com.ivor.kriptex.deliverypolicy.DeliveryPreferences
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.connection.ConnectionStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default decision engine.
 *
 * Mapping is performed via [DeliveryPolicy] (defaults to [DefaultDeliveryPolicy]) and then
 * post-processed to support:
 * - Passive -> Active upgrade automatically when state allows
 * - Never downgrading from Active unless required by state
 */
class DefaultDeliveryDecisionEngine(
    private val connectionStateProvider: ConnectionStateProvider,
    private val policy: DeliveryPolicy = DefaultDeliveryPolicy,
    prefs: DeliveryPreferences = DeliveryPreferences(),
) : DeliveryDecisionEngine {

    private val listeners = LinkedHashSet<(DeliveryStrategy) -> Unit>()
    private var isClosed: Boolean = false

    private var prefs: DeliveryPreferences = prefs

    private val _strategyFlow = MutableStateFlow<DeliveryStrategy>(
        policy.decide(connectionStateProvider.state, this.prefs),
    )
    override val strategyFlow: StateFlow<DeliveryStrategy> = _strategyFlow.asStateFlow()

    override val strategy: DeliveryStrategy
        get() = _strategyFlow.value

    private val unsubscribe = connectionStateProvider.addListener { newState ->
        if (isClosed) return@addListener
        onConnectionState(newState)
    }

    /**
     * Updates preferences and re-evaluates the current decision.
     * This is a pure state update; callers decide how to store/load preferences.
     */
    fun setPreferences(prefs: DeliveryPreferences) {
        this.prefs = prefs
        onConnectionState(connectionStateProvider.state)
    }

    override fun addListener(listener: (DeliveryStrategy) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(strategy)
        return { listeners.remove(listener) }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        unsubscribe()
        listeners.clear()
    }

    private fun onConnectionState(state: ConnectionState) {
        val previous = strategy
        val desired = policy.decide(state, prefs)
        val next = applyUpgradeDowngradeRules(previous, desired, state)

        if (next != previous) {
            _strategyFlow.value = next
            val snapshot = listeners.toList()
            snapshot.forEach { it(next) }
        }
    }

    private fun applyUpgradeDowngradeRules(
        previous: DeliveryStrategy,
        desired: DeliveryStrategy,
        state: ConnectionState,
    ): DeliveryStrategy {
        // Upgrade: always allow Passive -> Active when policy wants Active.
        if (previous is PassiveDelivery && desired is ActiveDelivery) {
            return desired
        }

        // Avoid downgrading from Active due to "Unknown" (transient, imperfect knowledge).
        if (previous is ActiveDelivery && desired is PassiveDelivery && state == ConnectionState.Unknown) {
            return previous
        }

        // Downgrade only when required by state (explicitly offline/unreachable, relay-only, etc.)
        if (previous is ActiveDelivery && desired is PassiveDelivery) {
            return if (isDowngradeRequiredByState(state)) desired else previous
        }

        return desired
    }

    private fun isDowngradeRequiredByState(state: ConnectionState): Boolean {
        return when (state) {
            ConnectionState.PeerOffline,
            ConnectionState.RelayReady,
            -> true

            ConnectionState.DirectReady,
            ConnectionState.DirectConnecting,
            ConnectionState.Unknown,
            -> false
        }
    }
}
