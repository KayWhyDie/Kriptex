package com.ivor.kriptex.deliverypolicy.routing

import com.ivor.kriptex.deliverypolicy.protocol.ProtocolInboundResult
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage

interface ProtocolMessageRouter {
    fun route(message: ProtocolMessage, context: RoutingContext): RoutingResult
}

data class RoutingContext(
    /** Transport-level peer id (e.g., contact identifier). */
    val peerId: String,
    /** Authenticated Ed25519 identity public key, if the message arrived in an established 1:1 session. */
    val authenticatedPeerIdentityPublicKey: ByteArray?,
    /** Whether this message came from a session envelope (vs raw handshake bytes). */
    val isSessionEnveloped: Boolean,
    /** Whether this processing is part of restore/replay (must not emit outbound side-effects). */
    val isRestore: Boolean,
    /** Monotonic receive timestamp. */
    val receivedAtElapsedMs: Long,
    /** Sender id used by protocol inbound pipeline (kept for compatibility). */
    val senderId: String,
)

sealed interface RoutingResult {
    val kind: ProtocolMessageKind

    data class Accepted(
        override val kind: ProtocolMessageKind,
        val inbound: ProtocolInboundResult?,
        val enqueuedOutbound: List<ProtocolMessage>,
        val target: String,
    ) : RoutingResult

    data class Rejected(
        override val kind: ProtocolMessageKind,
        val reason: String,
    ) : RoutingResult
}
