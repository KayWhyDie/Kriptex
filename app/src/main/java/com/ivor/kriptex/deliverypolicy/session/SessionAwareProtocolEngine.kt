package com.ivor.kriptex.deliverypolicy.session

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpProtocolDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.ProtocolDebugTrace
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSessionProtocolEngineSnapshot
import com.ivor.kriptex.deliverypolicy.protocol.AckMessage
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolDecoder
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolEncoder
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolInboundPipeline
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolInboundResult
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.protocol.SessionAcceptMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionInitMessage
import com.ivor.kriptex.deliverypolicy.protocol.UserMessage
import com.ivor.kriptex.deliverypolicy.routing.ProtocolMessageRouter
import com.ivor.kriptex.deliverypolicy.routing.RoutingContext
import com.ivor.kriptex.deliverypolicy.routing.RoutingResult
import com.ivor.kriptex.deliverypolicy.session.crypto.NoOpSessionCryptoDebugTrace
import com.ivor.kriptex.deliverypolicy.session.crypto.SessionCryptoDebugTrace
import com.ivor.kriptex.deliverypolicy.session.ratchet.NoOpRatchetDebugTrace
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetDebugTrace
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhPreKeyBundle

/**
 * Session-bound protocol engine.
 *
 * Wraps the existing protocol inbound pipeline (bytes -> ProtocolMessage -> ledger/store + pending ACKs)
 * and adds:
 * - session establishment (SessionInit/SessionAccept)
 * - session envelope + replay protection
 * - outbound enforcement (USER/ACK require established session)
 */
class SessionAwareProtocolEngine(
    private val inbound: ProtocolInboundPipeline,
    private val outbound: SessionBoundProtocolOutbound,
    private val sessionStore: InMemorySessionStore,
    private val protocolDecoder: ProtocolDecoder,
    private val protocolEncoder: ProtocolEncoder,
    private val router: ProtocolMessageRouter,
    private val envelopeCodec: SessionEnvelopeCodec = SessionEnvelopeCodec(),
    private val sessionIdGenerator: SessionIdGenerator,
    private val localIdentityPublicKey: ByteArray,
    private val responderNonceGenerator: () -> ByteArray,
    private val aeadSupport: SessionAeadSupport = DefaultSessionAeadSupport,
    private val clock: Clock = MonotonicClock,
    private val protocolDebug: ProtocolDebugTrace = NoOpProtocolDebugTrace,
    private val sessionDebug: SessionDebugTrace = NoOpSessionDebugTrace,
    private val cryptoDebug: SessionCryptoDebugTrace = NoOpSessionCryptoDebugTrace,
    private val ratchetDebug: RatchetDebugTrace = NoOpRatchetDebugTrace,
) {

    sealed interface InboundOutcome {
        data class Accepted(
            val inbound: ProtocolInboundResult,
            val enqueuedOutbound: List<ProtocolMessage>,
        ) : InboundOutcome

        data class Rejected(val reason: String) : InboundOutcome
    }

    /**
     * Initiator-side: creates and enqueues a SessionInit.
     */
    fun startSession(peerId: String, conversationId: String, initiatorNonce: ByteArray, peerBundle: X3dhPreKeyBundle): SessionInitMessage {
        val sessionId = sessionIdGenerator.nextSessionId()

        val preferred = aeadSupport.preferred().let { if (aeadSupport.supports(it)) it else SessionAeadAlgorithm.AES_256_GCM }
        val created = sessionStore.createLocalInit(
            peerId = peerId,
            conversationId = conversationId,
            sessionId = sessionId,
            preferredAeadAlgorithm = preferred,
            localIdentityPublicKey = localIdentityPublicKey,
            initiatorNonce = initiatorNonce,
            peerBundle = peerBundle,
        )

        return SessionInitMessage(
            messageId = sessionIdGenerator.nextSessionId() + ":init",
            conversationId = conversationId,
            createdAtElapsedMs = clock.nowMs(),
            sessionId = sessionId,
            aeadAlgorithm = preferred,
            initiatorIdentityPublicKey = localIdentityPublicKey,
            initiatorNonce = initiatorNonce,
            initiatorBasePublicKey = created.pendingRatchetDhPublicKey ?: byteArrayOf(),
            responderIdentityPublicKey = peerBundle.identityPublicKey,
            responderSignedPreKeyId = peerBundle.signedPreKeyId,
            responderSignedPreKeyPublicKey = peerBundle.signedPreKeyPublicKey,
            responderSignedPreKeySignature = peerBundle.signedPreKeySignature,
            responderOneTimePreKeyId = peerBundle.oneTimePreKeyId,
            responderOneTimePreKeyPublicKey = peerBundle.oneTimePreKeyPublicKey,
        )
    }

    /**
     * Outbound sending.
     *
     * USER/ACK messages are session-enveloped; session messages are allowed without an established session.
     */
    fun send(peerId: String, message: ProtocolMessage) = outbound.enqueue(peerId, message)

    /**
     * Main inbound entrypoint.
     */
    fun onInboundBytes(bytes: ByteArray, receivedAtElapsedMs: Long, peerId: String): InboundOutcome {
        // Session envelope path.
        if (envelopeCodec.looksLikeEnvelope(bytes)) {
            val env = try {
                envelopeCodec.decode(bytes)
            } catch (e: IllegalArgumentException) {
                sessionDebug.onMessageRejected(peerId, reason = "bad_envelope", elapsedMs = receivedAtElapsedMs)
                return InboundOutcome.Rejected("bad_envelope")
            }

            val s = sessionStore.findBySessionId(peerId, env.sessionId)
            if (s == null || !s.isEstablished()) {
                sessionDebug.onMessageRejected(peerId, reason = "unknown_session", elapsedMs = receivedAtElapsedMs)
                return InboundOutcome.Rejected("unknown_session")
            }

            when (val replay = sessionStore.acceptInboundSeq(peerId, env.sessionId, env.seq)) {
                is ReplayDecision.Rejected -> {
                    sessionDebug.onReplayDetected(peerId, env.sessionId, env.seq, receivedAtElapsedMs)
                    return InboundOutcome.Rejected(replay.reason)
                }

                is ReplayDecision.Accepted -> Unit
            }

            if (s.aeadEnabled && env.messageId == null) {
                sessionDebug.onMessageRejected(peerId, reason = "missing_message_id", elapsedMs = receivedAtElapsedMs)
                return InboundOutcome.Rejected("missing_message_id")
            }

            if (!s.aeadEnabled && env.messageId != null) {
                sessionDebug.onMessageRejected(peerId, reason = "unexpected_encrypted_envelope", elapsedMs = receivedAtElapsedMs)
                return InboundOutcome.Rejected("unexpected_encrypted_envelope")
            }

            // Legacy v1 envelope: inner is plaintext protocol bytes.
            val plaintext = if (env.messageId == null) {
                env.inner
            } else {
                // v2+ envelope: inner is CiphertextBlob bytes and messageId is cleartext.
                val ok = sessionStore.acceptInboundMessageId(peerId, env.sessionId, env.messageId)
                if (!ok) {
                    sessionDebug.onMessageRejected(peerId, reason = "reused_message_id", elapsedMs = receivedAtElapsedMs)
                    cryptoDebug.onDecryptRejected(env.sessionId, env.messageId, "reused_message_id")
                    return InboundOutcome.Rejected("reused_message_id")
                }

                try {
                    sessionStore.decryptSessionPayload(
                        peerId = peerId,
                        sessionId = env.sessionId,
                        messageId = env.messageId,
                        envelopeSeq = env.seq,
                        ciphertextBlobBytes = env.inner,
                        aeadDebug = cryptoDebug,
                        ratchetDebug = ratchetDebug,
                    )
                } catch (e: Exception) {
                    sessionDebug.onMessageRejected(peerId, reason = "decrypt_failed", elapsedMs = receivedAtElapsedMs)
                    return InboundOutcome.Rejected("decrypt_failed")
                }
            }

            val decoded = try {
                protocolDecoder.decode(plaintext)
            } catch (_: Exception) {
                sessionDebug.onMessageRejected(peerId, reason = "bad_protocol", elapsedMs = receivedAtElapsedMs)
                return InboundOutcome.Rejected("bad_protocol")
            }

            val routed = router.route(
                message = decoded,
                context = RoutingContext(
                    peerId = peerId,
                    authenticatedPeerIdentityPublicKey = s.peerIdentityPublicKey,
                    isSessionEnveloped = true,
                    isRestore = false,
                    receivedAtElapsedMs = receivedAtElapsedMs,
                    senderId = peerId,
                ),
            )

            return when (routed) {
                is RoutingResult.Rejected -> {
                    sessionDebug.onMessageRejected(peerId, reason = routed.reason, elapsedMs = receivedAtElapsedMs)
                    InboundOutcome.Rejected(routed.reason)
                }

                is RoutingResult.Accepted -> {
                    val inboundResult = routed.inbound ?: return InboundOutcome.Rejected("missing_inbound_result")
                    InboundOutcome.Accepted(inbound = inboundResult, enqueuedOutbound = routed.enqueuedOutbound)
                }
            }
        }

        // Raw protocol path (handshake only).
        val decoded = try {
            protocolDecoder.decode(bytes)
        } catch (e: IllegalArgumentException) {
            sessionDebug.onMessageRejected(peerId, reason = "bad_protocol", elapsedMs = receivedAtElapsedMs)
            return InboundOutcome.Rejected("bad_protocol")
        }

        val routed = router.route(
            message = decoded,
            context = RoutingContext(
                peerId = peerId,
                authenticatedPeerIdentityPublicKey = null,
                isSessionEnveloped = false,
                isRestore = false,
                receivedAtElapsedMs = receivedAtElapsedMs,
                senderId = peerId,
            ),
        )

        return when (routed) {
            is RoutingResult.Rejected -> {
                sessionDebug.onMessageRejected(peerId, reason = routed.reason, elapsedMs = receivedAtElapsedMs)
                InboundOutcome.Rejected(routed.reason)
            }

            is RoutingResult.Accepted -> {
                val inboundResult = routed.inbound ?: return InboundOutcome.Rejected("missing_inbound_result")
                InboundOutcome.Accepted(inbound = inboundResult, enqueuedOutbound = routed.enqueuedOutbound)
            }
        }
    }

    fun snapshot(): PersistedSessionProtocolEngineSnapshot {
        val capturedAt = clock.nowMs()
        val snap = PersistedSessionProtocolEngineSnapshot(
            capturedAtElapsedMs = capturedAt,
            sessionStore = sessionStore.snapshot(),
            protocolInbound = inbound.snapshot(),
        )
        return snap
    }

    fun restore(snapshot: PersistedSessionProtocolEngineSnapshot) {
        sessionStore.restore(snapshot.sessionStore)
        inbound.restore(snapshot.protocolInbound)
        // Restore must not auto-re-init or reset replay windows.
    }

    /**
     * Utility to create a session-bound envelope for a protocol message.
     * Used by tests.
     */
    fun wrapForSession(peerId: String, message: ProtocolMessage): ByteArray {
        val inner = protocolEncoder.encode(message)
        protocolDebug.onEncode(message.messageId, message.conversationId, message.type, clock.nowMs(), inner.size)
        val session = sessionStore.findEstablished(peerId, message.conversationId) ?: throw IllegalStateException("no_session")

        if (!session.aeadEnabled) {
            val seq = sessionStore.nextOutboundSeq(peerId, message.conversationId)
            return envelopeCodec.encode(
                SessionEnvelope(
                    sessionId = session.sessionId,
                    seq = seq,
                    messageId = null,
                    inner = inner,
                ),
            )
        }

        val enc = sessionStore.encryptSessionPayload(
            peerId = peerId,
            conversationId = message.conversationId,
            messageId = message.messageId,
            plaintextProtocolBytes = inner,
            aeadDebug = cryptoDebug,
            ratchetDebug = ratchetDebug,
        )
        return envelopeCodec.encode(
            SessionEnvelope(
                sessionId = enc.sessionId,
                seq = enc.seq,
                messageId = message.messageId,
                inner = enc.inner,
            ),
        )
    }
}
