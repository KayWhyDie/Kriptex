package com.ivor.kriptex.deliverypolicy.connection

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.ConnectionState
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Derives a stable [ConnectionState] from abstract signals.
 *
 * Mapping (in priority order, when localOnline=true):
 * 1) PeerOffline (if offline signal is within [peerOfflineHoldMs])
 * 2) DirectReady (if direct-confirmed is within [directFreshnessMs])
 * 3) DirectConnecting (if direct attempt is currently in progress)
 * 4) RelayReady (if relay is available)
 * 5) Unknown
 *
 * Stability rules:
 * - [ConnectionState.DirectReady] and [ConnectionState.PeerOffline] emit immediately.
 * - Other state changes are debounced by [debounceMs].
 * - Hold windows are time-based; call [refresh] to let them expire without new signals.
 */
class DefaultConnectionStateProvider(
    private val clock: Clock = MonotonicClock,
    private val debounceMs: Long = 500L,
    private val directFreshnessMs: Long = 15_000L,
    private val peerOfflineHoldMs: Long = 10_000L,
) : ConnectionStateProvider, ConnectionSignalSink {

    private var localOnline: Boolean = false
    private var relayAvailable: Boolean = false
    private var directAttemptInProgress: Boolean = false

    private var lastDirectConfirmedAtMs: Long? = null
    private var lastPeerOfflineAtMs: Long? = null

    private val listeners = LinkedHashSet<(ConnectionState) -> Unit>()

    private val _stateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Unknown)
    override val stateFlow: StateFlow<ConnectionState> = _stateFlow.asStateFlow()

    override val state: ConnectionState
        get() = _stateFlow.value

    private var pendingState: ConnectionState? = null
    private var pendingSinceMs: Long = 0L

    override fun addListener(listener: (ConnectionState) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }

    override fun setLocalOnline(isOnline: Boolean) {
        localOnline = isOnline
        reevaluateAndMaybeEmit()
    }

    override fun setRelayAvailable(isAvailable: Boolean) {
        relayAvailable = isAvailable
        reevaluateAndMaybeEmit()
    }

    override fun setDirectAttemptInProgress(inProgress: Boolean) {
        directAttemptInProgress = inProgress
        reevaluateAndMaybeEmit()
    }

    override fun reportDirectContactConfirmed() {
        lastDirectConfirmedAtMs = clock.nowMs()
        // A confirmed direct contact also implies “not offline” for now.
        lastPeerOfflineAtMs = null
        reevaluateAndMaybeEmit()
    }

    override fun reportPeerOffline() {
        lastPeerOfflineAtMs = clock.nowMs()
        reevaluateAndMaybeEmit()
    }

    override fun refresh() {
        reevaluateAndMaybeEmit()
    }

    private fun reevaluateAndMaybeEmit() {
        val now = clock.nowMs()
        val raw = computeRawState(now)
        val stable = state

        if (raw == stable) {
            pendingState = null
            return
        }

        if (shouldBypassDebounce(raw)) {
            pendingState = null
            setStable(raw)
            return
        }

        if (debounceMs <= 0L) {
            pendingState = null
            setStable(raw)
            return
        }

        if (pendingState != raw) {
            pendingState = raw
            pendingSinceMs = now
            return
        }

        val elapsed = now - pendingSinceMs
        if (elapsed >= debounceMs) {
            pendingState = null
            setStable(raw)
        }
    }

    private fun computeRawState(nowMs: Long): ConnectionState {
        if (!localOnline) return ConnectionState.Unknown

        val offlineAt = lastPeerOfflineAtMs
        if (offlineAt != null && nowMs - offlineAt <= peerOfflineHoldMs) {
            return ConnectionState.PeerOffline
        }

        val directAt = lastDirectConfirmedAtMs
        if (directAt != null && nowMs - directAt <= directFreshnessMs) {
            return ConnectionState.DirectReady
        }

        if (directAttemptInProgress) {
            return ConnectionState.DirectConnecting
        }

        if (relayAvailable) {
            return ConnectionState.RelayReady
        }

        return ConnectionState.Unknown
    }

    private fun shouldBypassDebounce(state: ConnectionState): Boolean {
        return when (state) {
            ConnectionState.DirectReady,
            ConnectionState.PeerOffline,
            -> true

            else -> false
        }
    }

    private fun setStable(newState: ConnectionState) {
        if (newState == state) return
        _stateFlow.value = newState
        // Snapshot to avoid concurrent modification if listener removes itself.
        val snapshot = listeners.toList()
        snapshot.forEach { it(newState) }
    }
}
