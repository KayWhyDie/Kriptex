package com.ivor.kriptex.deliverypolicy

import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeClock(private var nowMs: Long = 0L) : Clock {
    override fun nowMs(): Long = nowMs

    fun advanceBy(deltaMs: Long) {
        nowMs += deltaMs
    }
}

class DefaultDeliveryPolicyTest {
    @Test
    fun auto_directReady_prefersRealtime_returnsActive() {
        val prefs = DeliveryPreferences(
            mode = DeliveryModePreference.AUTO,
            preferRealtime = true,
        )

        val result = DefaultDeliveryPolicy.decide(ConnectionState.DirectReady, prefs)
        assertEquals(ActiveDelivery, result)
    }

    @Test
    fun auto_directReady_notRealtime_returnsPassiveUserPreference() {
        val prefs = DeliveryPreferences(
            mode = DeliveryModePreference.AUTO,
            preferRealtime = false,
        )

        val result = DefaultDeliveryPolicy.decide(ConnectionState.DirectReady, prefs)
        assertEquals(PassiveDelivery(PassiveDelivery.QueueReason.USER_PREFERENCE), result)
    }

    @Test
    fun passiveOnly_alwaysReturnsPassiveUserPreference() {
        val prefs = DeliveryPreferences(mode = DeliveryModePreference.PASSIVE_ONLY)

        val result = DefaultDeliveryPolicy.decide(ConnectionState.DirectReady, prefs)
        assertEquals(PassiveDelivery(PassiveDelivery.QueueReason.USER_PREFERENCE), result)
    }

    @Test
    fun activeOnly_alwaysReturnsActive() {
        val prefs = DeliveryPreferences(mode = DeliveryModePreference.ACTIVE_ONLY)

        val result = DefaultDeliveryPolicy.decide(ConnectionState.PeerOffline, prefs)
        assertEquals(ActiveDelivery, result)
    }

    @Test
    fun relayReady_disallowedRelay_returnsPassiveNoRoute() {
        val prefs = DeliveryPreferences(
            mode = DeliveryModePreference.AUTO,
            allowRelay = false,
        )

        val result = DefaultDeliveryPolicy.decide(ConnectionState.RelayReady, prefs)
        assertEquals(PassiveDelivery(PassiveDelivery.QueueReason.NO_ROUTE), result)
    }
}

class DeliveryOrchestratorTest {
    @Test
    fun connecting_fallsBackToPassiveAfterGrace_thenUpgradesToActiveWhenReady() {
        val clock = FakeClock(nowMs = 0L)
        val orchestrator = DeliveryOrchestrator(
            clock = clock,
            config = DeliveryOrchestratorConfig(connectingGraceMs = 3_000L),
        )

        val prefs = DeliveryPreferences(
            mode = DeliveryModePreference.AUTO,
            preferRealtime = true,
        )

        assertEquals(ActiveDelivery, orchestrator.evaluate(ConnectionState.DirectConnecting, prefs))

        clock.advanceBy(2_500L)
        assertEquals(ActiveDelivery, orchestrator.evaluate(ConnectionState.DirectConnecting, prefs))

        clock.advanceBy(700L)
        assertEquals(
            PassiveDelivery(PassiveDelivery.QueueReason.CONNECTING_TIMEOUT),
            orchestrator.evaluate(ConnectionState.DirectConnecting, prefs),
        )

        assertEquals(ActiveDelivery, orchestrator.evaluate(ConnectionState.DirectReady, prefs))
    }
}
