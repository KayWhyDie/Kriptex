package com.ivor.kriptex.deliverypolicy.connection

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now

    fun advanceBy(deltaMs: Long) {
        now += deltaMs
    }
}

class DefaultConnectionStateProviderTest {
    @Test
    fun relayReady_isDebounced_andDoesNotEmitImmediately() {
        val clock = FakeClock(0L)
        val provider = DefaultConnectionStateProvider(
            clock = clock,
            debounceMs = 500L,
            directFreshnessMs = 15_000L,
            peerOfflineHoldMs = 10_000L,
        )

        provider.setLocalOnline(true)
        provider.setRelayAvailable(true)

        // Debounced: should still be Unknown right away.
        assertEquals(ConnectionState.Unknown, provider.state)

        clock.advanceBy(499L)
        provider.refresh()
        assertEquals(ConnectionState.Unknown, provider.state)

        clock.advanceBy(2L)
        provider.refresh()
        assertEquals(ConnectionState.RelayReady, provider.state)
    }

    @Test
    fun directReady_bypassesDebounce_andEmitsImmediately() {
        val clock = FakeClock(0L)
        val provider = DefaultConnectionStateProvider(
            clock = clock,
            debounceMs = 10_000L, // huge debounce to prove bypass
        )

        provider.setLocalOnline(true)
        provider.setRelayAvailable(true)

        // Still debounced; not yet RelayReady.
        assertEquals(ConnectionState.Unknown, provider.state)

        provider.reportDirectContactConfirmed()
        assertEquals(ConnectionState.DirectReady, provider.state)
    }

    @Test
    fun peerOffline_isImmediate_thenHolds_thenExpires_withDebounce() {
        val clock = FakeClock(0L)
        val provider = DefaultConnectionStateProvider(
            clock = clock,
            debounceMs = 500L,
            peerOfflineHoldMs = 1_000L,
        )

        provider.setLocalOnline(true)
        provider.reportPeerOffline()
        assertEquals(ConnectionState.PeerOffline, provider.state)

        clock.advanceBy(900L)
        provider.refresh()
        assertEquals(ConnectionState.PeerOffline, provider.state)

        // Offline hold expires, so raw becomes Unknown; Unknown is debounced.
        clock.advanceBy(200L)
        provider.refresh()
        assertEquals(ConnectionState.PeerOffline, provider.state)

        clock.advanceBy(500L)
        provider.refresh()
        assertEquals(ConnectionState.Unknown, provider.state)
    }

    @Test
    fun listener_receivesOnlyMeaningfulTransitions() {
        val clock = FakeClock(0L)
        val provider = DefaultConnectionStateProvider(clock = clock, debounceMs = 500L)

        val seen = mutableListOf<ConnectionState>()
        provider.addListener { seen.add(it) }

        provider.setLocalOnline(true)
        provider.setRelayAvailable(true)
        provider.setRelayAvailable(false)
        provider.setRelayAvailable(true)

        // Still within debounce window; should not have emitted RelayReady yet.
        assertEquals(ConnectionState.Unknown, provider.state)
        assertEquals(listOf(ConnectionState.Unknown), seen)

        clock.advanceBy(500L)
        provider.refresh()

        assertEquals(ConnectionState.RelayReady, provider.state)
        assertTrue(seen.contains(ConnectionState.RelayReady))
        // Only initial + final is the important invariant; no flapping spam.
        assertEquals(2, seen.size)
    }
}
