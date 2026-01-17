package com.ivor.kriptex.deliverypolicy.diagnostics

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.ConnectionState
import com.ivor.kriptex.deliverypolicy.DeliveryModePreference
import com.ivor.kriptex.deliverypolicy.DeliveryPreferences
import com.ivor.kriptex.deliverypolicy.connection.DefaultConnectionStateProvider
import com.ivor.kriptex.deliverypolicy.decision.DefaultDeliveryDecisionEngine
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now

    fun advanceBy(deltaMs: Long) {
        now += deltaMs
    }
}

class DeliveryDebugTraceTest {
    @Test
    fun dumps_expected_transitions_in_order_for_realistic_sequence() {
        val clock = FakeClock(0L)

        val connectionProvider = DefaultConnectionStateProvider(
            clock = clock,
            debounceMs = 500L,
            directFreshnessMs = 15_000L,
            peerOfflineHoldMs = 10_000L,
        )

        val engine = DefaultDeliveryDecisionEngine(
            connectionStateProvider = connectionProvider,
            prefs = DeliveryPreferences(mode = DeliveryModePreference.AUTO, preferRealtime = true),
        )

        val trace = DefaultDeliveryDebugTrace(
            connectionStateProvider = connectionProvider,
            decisionEngine = engine,
            clock = clock,
        )

        // Bring local online, then relay becomes available (debounced).
        connectionProvider.setLocalOnline(true)
        connectionProvider.setRelayAvailable(true)
        clock.advanceBy(500L)
        connectionProvider.refresh()

        // Peer explicitly offline (immediate).
        connectionProvider.reportPeerOffline()

        // Direct contact confirmed (immediate), should upgrade Passive -> Active.
        connectionProvider.reportDirectContactConfirmed()

        // Offline again (immediate), should downgrade Active -> Passive.
        connectionProvider.reportPeerOffline()

        val report = trace.dumpDebugReport()

        // Assert ordering by substring positions.
        val p1 = report.indexOf("CONNECTION UNKNOWN -> RELAY_READY")
        val p2 = report.indexOf("STRATEGY PASSIVE(reason=UNKNOWN) -> PASSIVE(reason=RELAY_ONLY)")
        val p3 = report.indexOf("CONNECTION RELAY_READY -> PEER_OFFLINE")
        val p4 = report.indexOf("STRATEGY PASSIVE(reason=RELAY_ONLY) -> PASSIVE(reason=PEER_OFFLINE)")
        val p5 = report.indexOf("CONNECTION PEER_OFFLINE -> DIRECT_READY")
        val p6 = report.indexOf("STRATEGY PASSIVE(reason=PEER_OFFLINE) -> ACTIVE [UPGRADE]")
        val p7 = report.indexOf("CONNECTION DIRECT_READY -> PEER_OFFLINE")
        val p8 = report.indexOf("STRATEGY ACTIVE -> PASSIVE(reason=PEER_OFFLINE) [DOWNGRADE]")

        assertTrue("expected relay transition in report", p1 >= 0)
        assertTrue("expected strategy relay decision", p2 >= 0)
        assertTrue("expected offline transition", p3 >= 0)
        assertTrue("expected offline strategy", p4 >= 0)
        assertTrue("expected direct ready transition", p5 >= 0)
        assertTrue("expected upgrade to active", p6 >= 0)
        assertTrue("expected downgrade transition", p7 >= 0)
        assertTrue("expected downgrade to passive", p8 >= 0)

        assertTrue(p1 < p2)
        assertTrue(p2 < p3)
        assertTrue(p3 < p4)
        assertTrue(p4 < p5)
        assertTrue(p5 < p6)
        assertTrue(p6 < p7)
        assertTrue(p7 < p8)

        trace.close()
        engine.close()
    }
}
