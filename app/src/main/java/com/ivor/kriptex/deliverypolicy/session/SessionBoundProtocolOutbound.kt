package com.ivor.kriptex.deliverypolicy.session

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpProtocolDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.ProtocolDebugTrace
import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult
import com.ivor.kriptex.deliverypolicy.outbox.MessageOutbox
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolEncoder
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionAcceptMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionInitMessage
import com.ivor.kriptex.deliverypolicy.session.crypto.NoOpSessionCryptoDebugTrace
import com.ivor.kriptex.deliverypolicy.session.crypto.SessionCryptoDebugTrace
import com.ivor.kriptex.deliverypolicy.session.ratchet.NoOpRatchetDebugTrace
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetDebugTrace

/**
 * Outbound sender that enforces session requirements and adds the session envelope.
 */
class SessionBoundProtocolOutbound(
    private val outbox: MessageOutbox,
    private val encoder: ProtocolEncoder,
    private val envelopeCodec: SessionEnvelopeCodec = SessionEnvelopeCodec(),
    private val sessionStore: InMemorySessionStore,
    private val clock: Clock = MonotonicClock,
    private val protocolDebug: ProtocolDebugTrace = NoOpProtocolDebugTrace,
    private val sessionDebug: SessionDebugTrace = NoOpSessionDebugTrace,
    private val cryptoDebug: SessionCryptoDebugTrace = NoOpSessionCryptoDebugTrace,
    private val ratchetDebug: RatchetDebugTrace = NoOpRatchetDebugTrace,
) {

    fun enqueue(peerId: String, message: ProtocolMessage): EnqueueResult {
        val now = clock.nowMs()
        val inner = encoder.encode(message)
        protocolDebug.onEncode(message.messageId, message.conversationId, message.type, now, inner.size)

        val bytes = when (message) {
            is SessionInitMessage,
            is SessionAcceptMessage,
            -> inner // handshake messages are not session-enveloped

            else -> {
                val session = sessionStore.findEstablished(peerId, message.conversationId)
                if (session == null) {
                    sessionDebug.onMessageRejected(peerId, reason = "no_session", elapsedMs = now)
                    return EnqueueResult.AlreadyEnqueued
                }

                if (!session.aeadEnabled) {
                    val seq = sessionStore.nextOutboundSeq(peerId, message.conversationId)
                    envelopeCodec.encode(
                        SessionEnvelope(
                            sessionId = session.sessionId,
                            seq = seq,
                            messageId = null,
                            inner = inner,
                        ),
                    )
                } else {
                    val enc = sessionStore.encryptSessionPayload(
                        peerId = peerId,
                        conversationId = message.conversationId,
                        messageId = message.messageId,
                        plaintextProtocolBytes = inner,
                        aeadDebug = cryptoDebug,
                        ratchetDebug = ratchetDebug,
                    )
                    envelopeCodec.encode(
                        SessionEnvelope(
                            sessionId = enc.sessionId,
                            seq = enc.seq,
                            messageId = message.messageId,
                            inner = enc.inner,
                        ),
                    )
                }
            }
        }

        return outbox.enqueue(
            OutgoingMessage(
                messageId = message.messageId,
                chatId = message.conversationId,
                payload = bytes,
                enqueueElapsedMs = now,
            ),
        )
    }
}
