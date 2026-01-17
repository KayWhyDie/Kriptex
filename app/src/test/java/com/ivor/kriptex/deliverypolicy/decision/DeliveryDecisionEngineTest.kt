package com.ivor.kriptex.deliverypolicy.decision

import com.ivor.kriptex.deliverypolicy.ActiveDelivery
import com.ivor.kriptex.deliverypolicy.ConnectionState
import com.ivor.kriptex.deliverypolicy.DeliveryPreferences
import com.ivor.kriptex.deliverypolicy.DeliveryModePreference
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.connection.ConnectionStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeConnectionStateProvider(initial: ConnectionState) : ConnectionStateProvider {
    private val listeners = LinkedHashSet<(ConnectionState) -> Unit>()
    private val _flow = MutableStateFlow(initial)

    override val state: ConnectionState
        get() = _flow.value

    override val stateFlow: StateFlow<ConnectionState>
        get() = _flow.asStateFlow()

    override fun addListener(listener: (ConnectionState) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }

    fun emit(newState: ConnectionState) {
        if (newState == state) return
        _flow.value = newState
        listeners.toList().forEach { it(newState) }
    }
}

class DeliveryDecisionEngineTest {
    @Test
    fun unknown_to_peerOffline_to_directReady_transitions_andUpgrades() {
        val provider = FakeConnectionStateProvider(ConnectionState.Unknown)
        val engine = DefaultDeliveryDecisionEngine(
            connectionStateProvider = provider,
            prefs = DeliveryPreferences(mode = DeliveryModePreference.AUTO, preferRealtime = true),
        )

        assertEquals(PassiveDelivery(PassiveDelivery.QueueReason.UNKNOWN), engine.strategy)

        provider.emit(ConnectionState.PeerOffline)
        assertEquals(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE), engine.strategy)

        provider.emit(ConnectionState.DirectReady)
        assertEquals(ActiveDelivery, engine.strategy)

        engine.close()
    }

    @Test
    fun passive_to_active_upgrade_isAutomatic() {
        val provider = FakeConnectionStateProvider(ConnectionState.PeerOffline)
        val engine = DefaultDeliveryDecisionEngine(
            connectionStateProvider = provider,
            prefs = DeliveryPreferences(mode = DeliveryModePreference.AUTO, preferRealtime = true),
        )

        assertEquals(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE), engine.strategy)

        provider.emit(ConnectionState.DirectReady)
        assertEquals(ActiveDelivery, engine.strategy)

        engine.close()
    }

    @Test
    fun active_to_passive_downgrade_happens_whenStateRequiresIt() {
        val provider = FakeConnectionStateProvider(ConnectionState.DirectReady)
        val engine = DefaultDeliveryDecisionEngine(
            connectionStateProvider = provider,
            prefs = DeliveryPreferences(mode = DeliveryModePreference.AUTO, preferRealtime = true),
        )

        assertEquals(ActiveDelivery, engine.strategy)

        provider.emit(ConnectionState.PeerOffline)
        assertEquals(PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE), engine.strategy)

        engine.close()
    }

    @Test
    fun active_isNotDowngraded_onUnknown() {
        val provider = FakeConnectionStateProvider(ConnectionState.DirectReady)
        val engine = DefaultDeliveryDecisionEngine(
            connectionStateProvider = provider,
            prefs = DeliveryPreferences(mode = DeliveryModePreference.AUTO, preferRealtime = true),
        )

        assertEquals(ActiveDelivery, engine.strategy)

        provider.emit(ConnectionState.Unknown)
        assertEquals(ActiveDelivery, engine.strategy)

        engine.close()
    }

    @Test
    fun emits_strategyChanges_toListeners_onlyWhenChanged() {
        val provider = FakeConnectionStateProvider(ConnectionState.Unknown)
        val engine = DefaultDeliveryDecisionEngine(
            connectionStateProvider = provider,
            prefs = DeliveryPreferences(mode = DeliveryModePreference.AUTO, preferRealtime = true),
        )

        val seen = mutableListOf<DeliveryStrategy>()
        val unsub = engine.addListener { seen.add(it) }

        provider.emit(ConnectionState.Unknown)
        provider.emit(ConnectionState.Unknown)
        provider.emit(ConnectionState.PeerOffline)
        provider.emit(ConnectionState.PeerOffline)
        provider.emit(ConnectionState.DirectReady)

        assertEquals(
            listOf(
                PassiveDelivery(PassiveDelivery.QueueReason.UNKNOWN),
                PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE),
                ActiveDelivery,
            ),
            seen,
        )

        unsub()
        engine.close()
    }
}
