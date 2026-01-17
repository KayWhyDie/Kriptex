package com.ivor.kriptex.deliverypolicy.routing

import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage

interface ProtocolHandshakeHandler {
    fun handleHandshake(message: ProtocolMessage, context: RoutingContext): HandshakeResult
}

sealed interface HandshakeResult {
    data class Accepted(
        val inboundBytesToStore: ByteArray,
        /** Optional outbound handshake response message (e.g., SessionAccept). */
        val outboundToSend: ProtocolMessage? = null,
    ) : HandshakeResult

    data class Rejected(val reason: String) : HandshakeResult
}
