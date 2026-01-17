package com.ivor.kriptex.deliverypolicy.session.routing

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolEncoder
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.protocol.SessionAcceptMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionInitMessage
import com.ivor.kriptex.deliverypolicy.routing.HandshakeResult
import com.ivor.kriptex.deliverypolicy.routing.ProtocolHandshakeHandler
import com.ivor.kriptex.deliverypolicy.routing.RoutingContext
import com.ivor.kriptex.deliverypolicy.session.InMemorySessionStore
import com.ivor.kriptex.deliverypolicy.session.SessionAeadSupport
import com.ivor.kriptex.deliverypolicy.session.SessionDebugTrace
import com.ivor.kriptex.deliverypolicy.session.NoOpSessionDebugTrace

class SessionHandshakeHandler(
    private val sessionStore: InMemorySessionStore,
    private val encoder: ProtocolEncoder,
    private val localIdentityPublicKey: ByteArray,
    private val responderNonceGenerator: () -> ByteArray,
    private val aeadSupport: SessionAeadSupport,
    private val clock: Clock = MonotonicClock,
    private val sessionDebug: SessionDebugTrace = NoOpSessionDebugTrace,
) : ProtocolHandshakeHandler {

    override fun handleHandshake(message: ProtocolMessage, context: RoutingContext): HandshakeResult {
        return when (message) {
            is SessionInitMessage -> onInit(message, context)
            is SessionAcceptMessage -> onAccept(message, context)
            else -> HandshakeResult.Rejected("not_a_handshake")
        }
    }

    private fun onInit(decoded: SessionInitMessage, context: RoutingContext): HandshakeResult {
        val responderNonce = responderNonceGenerator()

        val selected = if (aeadSupport.supports(decoded.aeadAlgorithm)) {
            decoded.aeadAlgorithm
        } else {
            SessionAeadAlgorithm.AES_256_GCM
        }

        val (established, confirmTag) = try {
            sessionStore.acceptRemoteInit(
                peerId = context.peerId,
                conversationId = decoded.conversationId,
                sessionId = decoded.sessionId,
                aeadAlgorithm = selected,
                localIdentityPublicKey = localIdentityPublicKey,
                initiatorPublicKey = decoded.initiatorIdentityPublicKey,
                initiatorNonce = decoded.initiatorNonce,
                initiatorBasePublicKey = decoded.initiatorBasePublicKey,
                responderIdentityPublicKey = decoded.responderIdentityPublicKey,
                responderSignedPreKeyId = decoded.responderSignedPreKeyId,
                responderSignedPreKeyPublicKey = decoded.responderSignedPreKeyPublicKey,
                responderSignedPreKeySignature = decoded.responderSignedPreKeySignature,
                responderOneTimePreKeyId = decoded.responderOneTimePreKeyId,
                responderOneTimePreKeyPublicKey = decoded.responderOneTimePreKeyPublicKey,
                responderNonce = responderNonce,
            )
        } catch (_: Exception) {
            sessionDebug.onMessageRejected(context.peerId, reason = "handshake_failed", elapsedMs = context.receivedAtElapsedMs)
            return HandshakeResult.Rejected("handshake_failed")
        }

        // Keep established for side-effects; do not remove.
        @Suppress("UNUSED_VARIABLE")
        val _keep = established

        val accept = SessionAcceptMessage(
            messageId = decoded.messageId + ":accept",
            conversationId = decoded.conversationId,
            createdAtElapsedMs = clock.nowMs(),
            sessionId = decoded.sessionId,
            aeadAlgorithm = selected,
            responderIdentityPublicKey = localIdentityPublicKey,
            responderNonce = responderNonce,
            initiatorIdentityPublicKey = decoded.initiatorIdentityPublicKey,
            initiatorNonce = decoded.initiatorNonce,
            initiatorBasePublicKey = decoded.initiatorBasePublicKey,
            responderSignedPreKeyId = decoded.responderSignedPreKeyId,
            responderOneTimePreKeyId = decoded.responderOneTimePreKeyId,
            confirmTag = confirmTag,
        )

        // Store init itself via protocol inbound pipeline (router will do it).
        val inboundBytes = encoder.encode(decoded)
        return HandshakeResult.Accepted(inboundBytesToStore = inboundBytes, outboundToSend = accept)
    }

    private fun onAccept(decoded: SessionAcceptMessage, context: RoutingContext): HandshakeResult {
        try {
            sessionStore.applyRemoteAccept(
                peerId = context.peerId,
                conversationId = decoded.conversationId,
                sessionId = decoded.sessionId,
                aeadAlgorithm = decoded.aeadAlgorithm,
                localIdentityPublicKey = localIdentityPublicKey,
                initiatorPublicKey = decoded.initiatorIdentityPublicKey,
                initiatorNonce = decoded.initiatorNonce,
                responderPublicKey = decoded.responderIdentityPublicKey,
                responderNonce = decoded.responderNonce,
                initiatorBasePublicKey = decoded.initiatorBasePublicKey,
                responderSignedPreKeyId = decoded.responderSignedPreKeyId,
                responderOneTimePreKeyId = decoded.responderOneTimePreKeyId,
                confirmTag = decoded.confirmTag,
            )
        } catch (_: Exception) {
            sessionDebug.onMessageRejected(context.peerId, reason = "handshake_failed", elapsedMs = context.receivedAtElapsedMs)
            return HandshakeResult.Rejected("handshake_failed")
        }

        val inboundBytes = encoder.encode(decoded)
        return HandshakeResult.Accepted(inboundBytesToStore = inboundBytes, outboundToSend = null)
    }
}
