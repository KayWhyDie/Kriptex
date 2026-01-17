package com.ivor.kriptex.deliverypolicy.connection

/**
 * Abstracted input signals used to derive [com.ivor.kriptex.deliverypolicy.ConnectionState].
 *
 * These are intentionally not “raw Tor/network” objects; callers translate their own events
 * into these signals.
 */
interface ConnectionSignalSink {
    /** True when local connectivity is ready (e.g., app online / transport ready). */
    fun setLocalOnline(isOnline: Boolean)

    /** True when a relay path is believed available (optional). */
    fun setRelayAvailable(isAvailable: Boolean)

    /** True while a direct connection attempt is in progress. */
    fun setDirectAttemptInProgress(inProgress: Boolean)

    /** Report that direct contact/handshake with the peer was confirmed “now”. */
    fun reportDirectContactConfirmed()

    /** Report that the peer is explicitly offline/unreachable “now”. */
    fun reportPeerOffline()

    /**
     * Forces re-evaluation using the current time.
     * Useful for expiring “hold windows” without requiring any new signals.
     */
    fun refresh()
}
