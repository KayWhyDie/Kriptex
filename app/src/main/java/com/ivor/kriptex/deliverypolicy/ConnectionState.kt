package com.ivor.kriptex.deliverypolicy

sealed interface ConnectionState {
    data object Unknown : ConnectionState

    /** Direct path is ready for realtime delivery. */
    data object DirectReady : ConnectionState

    /** Direct path is being established (not ready yet). */
    data object DirectConnecting : ConnectionState

    /** Relay path is available (direct is not). */
    data object RelayReady : ConnectionState

    /** Peer is believed to be offline/unreachable at the moment. */
    data object PeerOffline : ConnectionState
}
