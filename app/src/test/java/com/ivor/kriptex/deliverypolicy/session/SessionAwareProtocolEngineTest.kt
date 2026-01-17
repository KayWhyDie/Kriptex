package com.ivor.kriptex.deliverypolicy.session

import com.ivor.kriptex.deliverypolicy.ledger.InMemoryConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessage
import com.ivor.kriptex.deliverypolicy.messagestore.InMemoryConversationMessageStore
import com.ivor.kriptex.deliverypolicy.messagestore.adapters.ConversationMessageStoreOutboxAdapter
import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult
import com.ivor.kriptex.deliverypolicy.outbox.MessageOutbox
import com.ivor.kriptex.deliverypolicy.outbox.OutboxItem
import com.ivor.kriptex.deliverypolicy.outbox.OutboxSnapshot
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.persistence.PersistedMessageOutboxSnapshot
import com.ivor.kriptex.deliverypolicy.protocol.BinaryProtocolCodec
import com.ivor.kriptex.deliverypolicy.protocol.InMemoryProtocolInboundPipeline
import com.ivor.kriptex.deliverypolicy.protocol.IncrementingMessageIdGenerator
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.protocol.SessionAcceptMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionInitMessage
import com.ivor.kriptex.deliverypolicy.protocol.UserMessage
import com.ivor.kriptex.deliverypolicy.routing.DefaultProtocolMessageRouter
import com.ivor.kriptex.deliverypolicy.routing.outbound.SessionBoundOutboundEnqueuer
import com.ivor.kriptex.deliverypolicy.session.routing.SessionHandshakeHandler
import com.ivor.kriptex.deliverypolicy.session.x3dh.InMemoryX3dhPreKeyStore
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhCrypto
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhIdentityKeyPair
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhOneTimePreKey
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhPreKeyBundle
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhSignedPreKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingOutbox : MessageOutbox {
    private val _flow = MutableStateFlow(OutboxSnapshot(size = 0, items = emptyList()))
    override val snapshotFlow: StateFlow<OutboxSnapshot> = _flow
    override val snapshot: OutboxSnapshot
        get() = _flow.value

    val enqueued = ArrayList<OutgoingMessage>()

    override fun snapshot(): PersistedMessageOutboxSnapshot = PersistedMessageOutboxSnapshot(
        capturedAtElapsedMs = 0L,
        messages = emptyList(),
    )

    override fun restore(snapshot: PersistedMessageOutboxSnapshot) = Unit

    override fun enqueue(message: OutgoingMessage): EnqueueResult {
        enqueued.add(message)
        _flow.value = OutboxSnapshot(
            size = enqueued.size,
            items = enqueued.map {
                OutboxItem(
                    messageId = it.messageId,
                    chatId = it.chatId,
                    status = OutboxItem.Status.QUEUED,
                    enqueueElapsedMs = it.enqueueElapsedMs,
                )
            },
        )
        return EnqueueResult.Enqueued
    }

    override fun notifyDelivered(messageId: String): Boolean = false

    override fun notifyFailed(messageId: String, retryable: Boolean, reason: String?): Boolean = false

    override fun addListener(listener: (OutboxSnapshot) -> Unit): () -> Unit {
        listener(snapshot)
        return {}
    }

    override fun close() = Unit
}

class SessionAwareProtocolEngineTest {

    private object AesOnlySupport : SessionAeadSupport {
        override fun preferred(): SessionAeadAlgorithm = SessionAeadAlgorithm.AES_256_GCM
        override fun supports(algorithm: SessionAeadAlgorithm): Boolean = algorithm == SessionAeadAlgorithm.AES_256_GCM
    }

    private object InitiatorPrefersXChaCha : SessionAeadSupport {
        override fun preferred(): SessionAeadAlgorithm = SessionAeadAlgorithm.XCHACHA20_POLY1305
        override fun supports(algorithm: SessionAeadAlgorithm): Boolean = true
    }

    @Test
    fun session_init_accept_handshake_then_session_bound_user_generates_session_bound_ack() {
        val codec = BinaryProtocolCodec()

        data class Stack(
            val engine: SessionAwareProtocolEngine,
            val outbox: RecordingOutbox,
            val store: InMemoryConversationMessageStore,
            val sessions: InMemorySessionStore,
            val x3dhIdentity: X3dhIdentityKeyPair,
            val x3dhBundle: X3dhPreKeyBundle,
        )

        fun makeStack(aeadSupport: SessionAeadSupport): Stack {
            val store = InMemoryConversationMessageStore()
            val recording = RecordingOutbox()
            val outbox = ConversationMessageStoreOutboxAdapter(recording, store)
            val ledger = InMemoryConversationDeliveryLedger()
            val inbound = InMemoryProtocolInboundPipeline(
                decoder = codec,
                encoder = codec,
                messageIdGenerator = IncrementingMessageIdGenerator(prefix = "ack", start = 1),
                ledger = ledger,
                messageStore = store,
            )

            val id = X3dhCrypto.generateIdentityKeyPair()
            val spk = X3dhCrypto.generateX25519KeyPair()
            val spkId = 1
            val spkSig = X3dhCrypto.signSignedPreKey(identitySeed = id.seed, signedPreKeyId = spkId, signedPreKeyPublic = spk.publicKey)
            val signed = X3dhSignedPreKey(preKeyId = spkId, privateKey = spk.privateKey, publicKey = spk.publicKey, signature = spkSig, createdAtElapsedMs = 0L)
            val opk = X3dhCrypto.generateX25519KeyPair()
            val oneTime = X3dhOneTimePreKey(preKeyId = 1, privateKey = opk.privateKey, publicKey = opk.publicKey, createdAtElapsedMs = 0L)
            val preKeyStore = InMemoryX3dhPreKeyStore(signedPreKey = signed, oneTimePreKeys = listOf(oneTime))
            val bundle = preKeyStore.buildBundle(identityPublicKey = id.publicKey)

            val sessions = InMemorySessionStore(
                x3dhIdentitySeedEd = id.seed,
                x3dhIdentityPublicKeyEd = id.publicKey,
                x3dhPreKeyStore = preKeyStore,
            )
            val sessionOutbound = SessionBoundProtocolOutbound(
                outbox = outbox,
                encoder = codec,
                sessionStore = sessions,
            )

            val handshakeHandler = SessionHandshakeHandler(
                sessionStore = sessions,
                encoder = codec,
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(8, 8) },
                aeadSupport = aeadSupport,
            )
            val router = DefaultProtocolMessageRouter(
                encoder = codec,
                inbound = inbound,
                outbound = SessionBoundOutboundEnqueuer(sessionOutbound),
                handshakeHandler = handshakeHandler,
            )

            val engine = SessionAwareProtocolEngine(
                inbound = inbound,
                outbound = sessionOutbound,
                sessionStore = sessions,
                protocolDecoder = codec,
                protocolEncoder = codec,
                router = router,
                sessionIdGenerator = IncrementingSessionIdGenerator(prefix = "s", start = 1),
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(8, 8) },
                aeadSupport = aeadSupport,
            )

            return Stack(engine, recording, store, sessions, x3dhIdentity = id, x3dhBundle = bundle)
        }

        val a = makeStack(aeadSupport = AesOnlySupport)
        val b = makeStack(aeadSupport = AesOnlySupport)

        val init: SessionInitMessage = a.engine.startSession(
            peerId = "B",
            conversationId = "c1",
            initiatorNonce = byteArrayOf(7, 7),
            peerBundle = b.x3dhBundle,
        )
        b.engine.onInboundBytes(codec.encode(init), receivedAtElapsedMs = 1L, peerId = "A")

        // B should have enqueued SessionAccept (raw protocol) to its outbox.
        assertEquals(1, b.outbox.enqueued.size)
        val accept = codec.decode(b.outbox.enqueued.single().payload) as SessionAcceptMessage
        assertEquals(init.sessionId, accept.sessionId)
        assertEquals(SessionAeadAlgorithm.AES_256_GCM, accept.aeadAlgorithm)

        a.engine.onInboundBytes(codec.encode(accept), receivedAtElapsedMs = 2L, peerId = "B")

        // A sends a session-bound user message.
        val sendRes = a.engine.send(
            peerId = "B",
            message = UserMessage("u1", "c1", createdAtElapsedMs = 3L, payload = byteArrayOf(5)),
        )
        assertEquals(EnqueueResult.Enqueued, sendRes)

        // Deliver the user message bytes from A to B.
        val sentBytes = a.outbox.enqueued.single { it.messageId == "u1" }.payload
        val inboundRes = b.engine.onInboundBytes(sentBytes, receivedAtElapsedMs = 4L, peerId = "A")
        assertTrue(inboundRes is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        // B should have enqueued an ACK, and it must be session-enveloped (not raw protocol magic).
        val ackOutgoing = b.outbox.enqueued.last()
        assertTrue(SessionEnvelopeCodec().looksLikeEnvelope(ackOutgoing.payload))

        // Ordering in storeB: handshake messages may be recorded, but the inbound user must precede the outbound ACK.
        val timeline = b.store.conversationTimeline("c1")
        val ids = timeline.messages.map { it.messageId }
        assertTrue("expected at least two messages in timeline", ids.size >= 2)
        assertEquals("u1", ids[ids.size - 2])
        assertEquals(ackOutgoing.messageId, ids.last())
        assertEquals(ConversationMessage.State.RECEIVED, b.store.message("u1")!!.state)
    }

    @Test
    fun message_rejected_without_session() {
        val codec = BinaryProtocolCodec()
        val store = InMemoryConversationMessageStore()
        val out = RecordingOutbox()
        val outbox = ConversationMessageStoreOutboxAdapter(out, store)
        val ledger = InMemoryConversationDeliveryLedger()
        val inbound = InMemoryProtocolInboundPipeline(
            decoder = codec,
            encoder = codec,
            messageIdGenerator = IncrementingMessageIdGenerator(prefix = "ack", start = 1),
            ledger = ledger,
            messageStore = store,
        )

        val id = X3dhCrypto.generateIdentityKeyPair()
        val spk = X3dhCrypto.generateX25519KeyPair()
        val spkId = 1
        val spkSig = X3dhCrypto.signSignedPreKey(identitySeed = id.seed, signedPreKeyId = spkId, signedPreKeyPublic = spk.publicKey)
        val signed = X3dhSignedPreKey(preKeyId = spkId, privateKey = spk.privateKey, publicKey = spk.publicKey, signature = spkSig, createdAtElapsedMs = 0L)
        val preKeyStore = InMemoryX3dhPreKeyStore(signedPreKey = signed, oneTimePreKeys = emptyList())

        val sessions = InMemorySessionStore(
            x3dhIdentitySeedEd = id.seed,
            x3dhIdentityPublicKeyEd = id.publicKey,
            x3dhPreKeyStore = preKeyStore,
        )
        val outbound = SessionBoundProtocolOutbound(outbox, codec, sessionStore = sessions)

        val handshakeHandler = SessionHandshakeHandler(
            sessionStore = sessions,
            encoder = codec,
            localIdentityPublicKey = id.publicKey,
            responderNonceGenerator = { byteArrayOf(9) },
            aeadSupport = AesOnlySupport,
        )
        val router = DefaultProtocolMessageRouter(
            encoder = codec,
            inbound = inbound,
            outbound = SessionBoundOutboundEnqueuer(outbound),
            handshakeHandler = handshakeHandler,
        )

        val engine = SessionAwareProtocolEngine(
            inbound = inbound,
            outbound = outbound,
            sessionStore = sessions,
            protocolDecoder = codec,
            protocolEncoder = codec,
            router = router,
            sessionIdGenerator = IncrementingSessionIdGenerator(prefix = "s", start = 1),
            localIdentityPublicKey = id.publicKey,
            responderNonceGenerator = { byteArrayOf(9) },
            aeadSupport = AesOnlySupport,
        )

        val envBytes = SessionEnvelopeCodec().encode(
            SessionEnvelope(sessionId = "unknown", seq = 1L, messageId = "u1", inner = byteArrayOf(1, 2, 3)),
        )
        val r = engine.onInboundBytes(envBytes, receivedAtElapsedMs = 2L, peerId = "peer")
        assertTrue(r is SessionAwareProtocolEngine.InboundOutcome.Rejected)
    }

    @Test
    fun handshake_rejected_when_accept_confirm_tag_is_tampered() {
        val codec = BinaryProtocolCodec()

        data class Stack(val engine: SessionAwareProtocolEngine, val outbox: RecordingOutbox, val bundle: X3dhPreKeyBundle)

        fun makeStack(withOneTime: Boolean): Stack {
            val store = InMemoryConversationMessageStore()
            val recording = RecordingOutbox()
            val outbox = ConversationMessageStoreOutboxAdapter(recording, store)
            val ledger = InMemoryConversationDeliveryLedger()
            val inbound = InMemoryProtocolInboundPipeline(
                decoder = codec,
                encoder = codec,
                messageIdGenerator = IncrementingMessageIdGenerator(prefix = "ack", start = 1),
                ledger = ledger,
                messageStore = store,
            )

            val id = X3dhCrypto.generateIdentityKeyPair()
            val spk = X3dhCrypto.generateX25519KeyPair()
            val spkId = 1
            val spkSig = X3dhCrypto.signSignedPreKey(identitySeed = id.seed, signedPreKeyId = spkId, signedPreKeyPublic = spk.publicKey)
            val signed = X3dhSignedPreKey(preKeyId = spkId, privateKey = spk.privateKey, publicKey = spk.publicKey, signature = spkSig, createdAtElapsedMs = 0L)
            val oneTimes = if (withOneTime) {
                val opk = X3dhCrypto.generateX25519KeyPair()
                listOf(X3dhOneTimePreKey(preKeyId = 1, privateKey = opk.privateKey, publicKey = opk.publicKey, createdAtElapsedMs = 0L))
            } else {
                emptyList()
            }
            val preKeyStore = InMemoryX3dhPreKeyStore(signedPreKey = signed, oneTimePreKeys = oneTimes)
            val bundle = preKeyStore.buildBundle(identityPublicKey = id.publicKey)

            val sessions = InMemorySessionStore(
                x3dhIdentitySeedEd = id.seed,
                x3dhIdentityPublicKeyEd = id.publicKey,
                x3dhPreKeyStore = preKeyStore,
            )
            val outbound = SessionBoundProtocolOutbound(outbox, codec, sessionStore = sessions)

            val handshakeHandler = SessionHandshakeHandler(
                sessionStore = sessions,
                encoder = codec,
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(9) },
                aeadSupport = AesOnlySupport,
            )
            val router = DefaultProtocolMessageRouter(
                encoder = codec,
                inbound = inbound,
                outbound = SessionBoundOutboundEnqueuer(outbound),
                handshakeHandler = handshakeHandler,
            )

            val engine = SessionAwareProtocolEngine(
                inbound = inbound,
                outbound = outbound,
                sessionStore = sessions,
                protocolDecoder = codec,
                protocolEncoder = codec,
                router = router,
                sessionIdGenerator = IncrementingSessionIdGenerator(prefix = "s", start = 1),
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(9) },
                aeadSupport = AesOnlySupport,
            )

            return Stack(engine, recording, bundle)
        }

        val a = makeStack(withOneTime = false)
        val b = makeStack(withOneTime = true)

        val init = a.engine.startSession(peerId = "B", conversationId = "c1", initiatorNonce = byteArrayOf(7, 7), peerBundle = b.bundle)
        b.engine.onInboundBytes(codec.encode(init), receivedAtElapsedMs = 1L, peerId = "A")
        val accept = codec.decode(b.outbox.enqueued.single().payload) as SessionAcceptMessage

        val tamperedTag = accept.confirmTag.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val tampered = accept.copy(confirmTag = tamperedTag)

        val outcome = a.engine.onInboundBytes(codec.encode(tampered), receivedAtElapsedMs = 2L, peerId = "B")
        val rejected = outcome as SessionAwareProtocolEngine.InboundOutcome.Rejected
        assertEquals("handshake_failed", rejected.reason)
    }

    @Test
    fun one_time_prekey_reuse_is_rejected() {
        val codec = BinaryProtocolCodec()

        data class Stack(val engine: SessionAwareProtocolEngine, val outbox: RecordingOutbox, val bundle: X3dhPreKeyBundle)

        fun makeStack(withOneTime: Boolean): Stack {
            val store = InMemoryConversationMessageStore()
            val recording = RecordingOutbox()
            val outbox = ConversationMessageStoreOutboxAdapter(recording, store)
            val ledger = InMemoryConversationDeliveryLedger()
            val inbound = InMemoryProtocolInboundPipeline(
                decoder = codec,
                encoder = codec,
                messageIdGenerator = IncrementingMessageIdGenerator(prefix = "ack", start = 1),
                ledger = ledger,
                messageStore = store,
            )

            val id = X3dhCrypto.generateIdentityKeyPair()
            val spk = X3dhCrypto.generateX25519KeyPair()
            val spkId = 1
            val spkSig = X3dhCrypto.signSignedPreKey(identitySeed = id.seed, signedPreKeyId = spkId, signedPreKeyPublic = spk.publicKey)
            val signed = X3dhSignedPreKey(preKeyId = spkId, privateKey = spk.privateKey, publicKey = spk.publicKey, signature = spkSig, createdAtElapsedMs = 0L)
            val oneTimes = if (withOneTime) {
                val opk = X3dhCrypto.generateX25519KeyPair()
                listOf(X3dhOneTimePreKey(preKeyId = 1, privateKey = opk.privateKey, publicKey = opk.publicKey, createdAtElapsedMs = 0L))
            } else {
                emptyList()
            }
            val preKeyStore = InMemoryX3dhPreKeyStore(signedPreKey = signed, oneTimePreKeys = oneTimes)
            val bundle = preKeyStore.buildBundle(identityPublicKey = id.publicKey)

            val sessions = InMemorySessionStore(
                x3dhIdentitySeedEd = id.seed,
                x3dhIdentityPublicKeyEd = id.publicKey,
                x3dhPreKeyStore = preKeyStore,
            )
            val outbound = SessionBoundProtocolOutbound(outbox, codec, sessionStore = sessions)

            val handshakeHandler = SessionHandshakeHandler(
                sessionStore = sessions,
                encoder = codec,
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(9) },
                aeadSupport = AesOnlySupport,
            )
            val router = DefaultProtocolMessageRouter(
                encoder = codec,
                inbound = inbound,
                outbound = SessionBoundOutboundEnqueuer(outbound),
                handshakeHandler = handshakeHandler,
            )

            val engine = SessionAwareProtocolEngine(
                inbound = inbound,
                outbound = outbound,
                sessionStore = sessions,
                protocolDecoder = codec,
                protocolEncoder = codec,
                router = router,
                sessionIdGenerator = IncrementingSessionIdGenerator(prefix = "s", start = 1),
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(9) },
                aeadSupport = AesOnlySupport,
            )

            return Stack(engine, recording, bundle)
        }

        val a = makeStack(withOneTime = false)
        val b = makeStack(withOneTime = true)
        val bundleWithOpk = b.bundle
        assertTrue(bundleWithOpk.oneTimePreKeyId != null)

        // First init consumes the one-time prekey.
        val init1 = a.engine.startSession(peerId = "B", conversationId = "c1", initiatorNonce = byteArrayOf(7, 7), peerBundle = bundleWithOpk)
        val r1 = b.engine.onInboundBytes(codec.encode(init1), receivedAtElapsedMs = 1L, peerId = "A")
        assertTrue(r1 is SessionAwareProtocolEngine.InboundOutcome.Accepted)
        assertEquals(1, b.outbox.enqueued.size)

        // Second init reusing the same bundle must be rejected.
        val init2 = a.engine.startSession(peerId = "B", conversationId = "c1", initiatorNonce = byteArrayOf(7, 8), peerBundle = bundleWithOpk)
        val r2 = b.engine.onInboundBytes(codec.encode(init2), receivedAtElapsedMs = 2L, peerId = "A")
        val rejected = r2 as SessionAwareProtocolEngine.InboundOutcome.Rejected
        assertEquals("handshake_failed", rejected.reason)
        assertEquals(1, b.outbox.enqueued.size)
    }

    @Test
    fun replay_and_message_id_replay_detection() {
        val codec = BinaryProtocolCodec()

        // Build two stacks, establish a session, then validate both seq replay and messageId replay.
        data class Stack(
            val engine: SessionAwareProtocolEngine,
            val outbox: RecordingOutbox,
            val sessions: InMemorySessionStore,
            val x3dhBundle: X3dhPreKeyBundle,
        )

        fun makeStack(): Stack {
            val store = InMemoryConversationMessageStore()
            val recording = RecordingOutbox()
            val outbox = ConversationMessageStoreOutboxAdapter(recording, store)
            val ledger = InMemoryConversationDeliveryLedger()
            val inbound = InMemoryProtocolInboundPipeline(
                decoder = codec,
                encoder = codec,
                messageIdGenerator = IncrementingMessageIdGenerator(prefix = "ack", start = 1),
                ledger = ledger,
                messageStore = store,
            )

            val id = X3dhCrypto.generateIdentityKeyPair()
            val spk = X3dhCrypto.generateX25519KeyPair()
            val spkId = 1
            val spkSig = X3dhCrypto.signSignedPreKey(identitySeed = id.seed, signedPreKeyId = spkId, signedPreKeyPublic = spk.publicKey)
            val signed = X3dhSignedPreKey(preKeyId = spkId, privateKey = spk.privateKey, publicKey = spk.publicKey, signature = spkSig, createdAtElapsedMs = 0L)
            val opk = X3dhCrypto.generateX25519KeyPair()
            val oneTime = X3dhOneTimePreKey(preKeyId = 1, privateKey = opk.privateKey, publicKey = opk.publicKey, createdAtElapsedMs = 0L)
            val preKeyStore = InMemoryX3dhPreKeyStore(signedPreKey = signed, oneTimePreKeys = listOf(oneTime))
            val bundle = preKeyStore.buildBundle(identityPublicKey = id.publicKey)

            val sessions = InMemorySessionStore(
                x3dhIdentitySeedEd = id.seed,
                x3dhIdentityPublicKeyEd = id.publicKey,
                x3dhPreKeyStore = preKeyStore,
            )
            val sessionOutbound = SessionBoundProtocolOutbound(outbox = outbox, encoder = codec, sessionStore = sessions)

            val handshakeHandler = SessionHandshakeHandler(
                sessionStore = sessions,
                encoder = codec,
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(9) },
                aeadSupport = AesOnlySupport,
            )
            val router = DefaultProtocolMessageRouter(
                encoder = codec,
                inbound = inbound,
                outbound = SessionBoundOutboundEnqueuer(sessionOutbound),
                handshakeHandler = handshakeHandler,
            )
            val engine = SessionAwareProtocolEngine(
                inbound = inbound,
                outbound = sessionOutbound,
                sessionStore = sessions,
                protocolDecoder = codec,
                protocolEncoder = codec,
                router = router,
                sessionIdGenerator = IncrementingSessionIdGenerator(prefix = "s", start = 1),
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(9) },
                aeadSupport = AesOnlySupport,
            )
            return Stack(engine, recording, sessions, x3dhBundle = bundle)
        }

        val a = makeStack()
        val b = makeStack()

        val init = a.engine.startSession(peerId = "B", conversationId = "c1", initiatorNonce = byteArrayOf(7, 7), peerBundle = b.x3dhBundle)
        b.engine.onInboundBytes(codec.encode(init), receivedAtElapsedMs = 1L, peerId = "A")
        val accept = codec.decode(b.outbox.enqueued.single().payload) as SessionAcceptMessage
        a.engine.onInboundBytes(codec.encode(accept), receivedAtElapsedMs = 2L, peerId = "B")

        // Send first encrypted user envelope.
        a.engine.send(peerId = "B", message = UserMessage("u1", "c1", 3L, payload = byteArrayOf(1)))
        val env1 = a.outbox.enqueued.single { it.messageId == "u1" }.payload
        val r1 = b.engine.onInboundBytes(env1, receivedAtElapsedMs = 4L, peerId = "A")
        assertTrue(r1 is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        // Re-send same envelope seq=1 -> replay reject.
        val r2 = b.engine.onInboundBytes(env1, receivedAtElapsedMs = 5L, peerId = "A")
        assertTrue(r2 is SessionAwareProtocolEngine.InboundOutcome.Rejected)

        // Re-encrypt same messageId with a new seq -> messageId replay reject (even though seq is fresh).
        val inner = codec.encode(UserMessage("u1", "c1", 3L, payload = byteArrayOf(1)))
        val enc2 = a.sessions.encryptSessionPayload(
            peerId = "B",
            conversationId = "c1",
            messageId = "u1",
            plaintextProtocolBytes = inner,
        )
        val env2 = SessionEnvelopeCodec().encode(SessionEnvelope(sessionId = enc2.sessionId, seq = enc2.seq, messageId = "u1", inner = enc2.inner))

        val r3 = b.engine.onInboundBytes(env2, receivedAtElapsedMs = 6L, peerId = "A")
        val rej = r3 as SessionAwareProtocolEngine.InboundOutcome.Rejected
        assertEquals("reused_message_id", rej.reason)
    }

    @Test
    fun restore_preserves_sessions_and_replay_window_no_auto_reinit() {
        val codec = BinaryProtocolCodec()

        data class Stack(
            val engine: SessionAwareProtocolEngine,
            val outbox: RecordingOutbox,
            val x3dhBundle: X3dhPreKeyBundle,
        )

        fun makeStack(responderNonce: ByteArray): Stack {
            val store = InMemoryConversationMessageStore()
            val recording = RecordingOutbox()
            val outbox = ConversationMessageStoreOutboxAdapter(recording, store)
            val ledger = InMemoryConversationDeliveryLedger()
            val inbound = InMemoryProtocolInboundPipeline(
                decoder = codec,
                encoder = codec,
                messageIdGenerator = IncrementingMessageIdGenerator(prefix = "ack", start = 1),
                ledger = ledger,
                messageStore = store,
            )

            val id = X3dhCrypto.generateIdentityKeyPair()
            val spk = X3dhCrypto.generateX25519KeyPair()
            val spkId = 1
            val spkSig = X3dhCrypto.signSignedPreKey(identitySeed = id.seed, signedPreKeyId = spkId, signedPreKeyPublic = spk.publicKey)
            val signed = X3dhSignedPreKey(preKeyId = spkId, privateKey = spk.privateKey, publicKey = spk.publicKey, signature = spkSig, createdAtElapsedMs = 0L)
            val opk = X3dhCrypto.generateX25519KeyPair()
            val oneTime = X3dhOneTimePreKey(preKeyId = 1, privateKey = opk.privateKey, publicKey = opk.publicKey, createdAtElapsedMs = 0L)
            val preKeyStore = InMemoryX3dhPreKeyStore(signedPreKey = signed, oneTimePreKeys = listOf(oneTime))
            val bundle = preKeyStore.buildBundle(identityPublicKey = id.publicKey)

            val sessions = InMemorySessionStore(
                x3dhIdentitySeedEd = id.seed,
                x3dhIdentityPublicKeyEd = id.publicKey,
                x3dhPreKeyStore = preKeyStore,
            )

            val outbound = SessionBoundProtocolOutbound(outbox, codec, sessionStore = sessions)

            val handshakeHandler = SessionHandshakeHandler(
                sessionStore = sessions,
                encoder = codec,
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { responderNonce },
                aeadSupport = AesOnlySupport,
            )
            val router = DefaultProtocolMessageRouter(
                encoder = codec,
                inbound = inbound,
                outbound = SessionBoundOutboundEnqueuer(outbound),
                handshakeHandler = handshakeHandler,
            )
            val engine = SessionAwareProtocolEngine(
                inbound = inbound,
                outbound = outbound,
                sessionStore = sessions,
                protocolDecoder = codec,
                protocolEncoder = codec,
                router = router,
                sessionIdGenerator = IncrementingSessionIdGenerator(prefix = "s", start = 1),
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { responderNonce },
                aeadSupport = AesOnlySupport,
            )
            return Stack(engine, recording, x3dhBundle = bundle)
        }

        val a = makeStack(responderNonce = byteArrayOf(8, 8))
        val b = makeStack(responderNonce = byteArrayOf(9, 9))

        val init = a.engine.startSession(peerId = "B", conversationId = "c1", initiatorNonce = byteArrayOf(7, 7), peerBundle = b.x3dhBundle)
        b.engine.onInboundBytes(codec.encode(init), receivedAtElapsedMs = 1L, peerId = "A")
        val accept = codec.decode(b.outbox.enqueued.single().payload) as SessionAcceptMessage
        a.engine.onInboundBytes(codec.encode(accept), receivedAtElapsedMs = 2L, peerId = "B")

        a.engine.send(peerId = "B", message = UserMessage("u1", "c1", 3L, payload = byteArrayOf(1)))
        val env1 = a.outbox.enqueued.single { it.messageId == "u1" }.payload

        val r1 = b.engine.onInboundBytes(env1, receivedAtElapsedMs = 3L, peerId = "A")
        assertTrue(r1 is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        val snap = b.engine.snapshot()

        val b2 = makeStack(responderNonce = byteArrayOf(9, 9))
        b2.engine.restore(snap)

        // Restore should not have enqueued anything.
        assertEquals(0, b2.outbox.enqueued.size)

        // Should reject same sequence after restore (replay window preserved).
        val r2 = b2.engine.onInboundBytes(env1, receivedAtElapsedMs = 4L, peerId = "A")
        assertTrue(r2 is SessionAwareProtocolEngine.InboundOutcome.Rejected)

        // Still should not have enqueued anything.
        assertEquals(0, b2.outbox.enqueued.size)
    }

    @Test
    fun tamper_detected_by_aead() {
        val codec = BinaryProtocolCodec()

        data class Stack(val engine: SessionAwareProtocolEngine, val outbox: RecordingOutbox, val bundle: X3dhPreKeyBundle)

        fun makeStack(): Stack {
            val store = InMemoryConversationMessageStore()
            val recording = RecordingOutbox()
            val outbox = ConversationMessageStoreOutboxAdapter(recording, store)
            val ledger = InMemoryConversationDeliveryLedger()
            val inbound = InMemoryProtocolInboundPipeline(
                decoder = codec,
                encoder = codec,
                messageIdGenerator = IncrementingMessageIdGenerator(prefix = "ack", start = 1),
                ledger = ledger,
                messageStore = store,
            )

            val id = X3dhCrypto.generateIdentityKeyPair()
            val spk = X3dhCrypto.generateX25519KeyPair()
            val spkId = 1
            val spkSig = X3dhCrypto.signSignedPreKey(identitySeed = id.seed, signedPreKeyId = spkId, signedPreKeyPublic = spk.publicKey)
            val signed = X3dhSignedPreKey(preKeyId = spkId, privateKey = spk.privateKey, publicKey = spk.publicKey, signature = spkSig, createdAtElapsedMs = 0L)
            val preKeyStore = InMemoryX3dhPreKeyStore(signedPreKey = signed, oneTimePreKeys = emptyList())
            val bundle = preKeyStore.buildBundle(identityPublicKey = id.publicKey)

            val sessions = InMemorySessionStore(
                x3dhIdentitySeedEd = id.seed,
                x3dhIdentityPublicKeyEd = id.publicKey,
                x3dhPreKeyStore = preKeyStore,
            )
            val outbound = SessionBoundProtocolOutbound(outbox = outbox, encoder = codec, sessionStore = sessions)

            val handshakeHandler = SessionHandshakeHandler(
                sessionStore = sessions,
                encoder = codec,
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(8, 8) },
                aeadSupport = AesOnlySupport,
            )
            val router = DefaultProtocolMessageRouter(
                encoder = codec,
                inbound = inbound,
                outbound = SessionBoundOutboundEnqueuer(outbound),
                handshakeHandler = handshakeHandler,
            )
            val engine = SessionAwareProtocolEngine(
                inbound = inbound,
                outbound = outbound,
                sessionStore = sessions,
                protocolDecoder = codec,
                protocolEncoder = codec,
                router = router,
                sessionIdGenerator = IncrementingSessionIdGenerator(prefix = "s", start = 1),
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(8, 8) },
                aeadSupport = AesOnlySupport,
            )
            return Stack(engine, recording, bundle)
        }

        val a = makeStack()
        val b = makeStack()

        val init = a.engine.startSession(peerId = "B", conversationId = "c1", initiatorNonce = byteArrayOf(7, 7), peerBundle = b.bundle)
        b.engine.onInboundBytes(codec.encode(init), receivedAtElapsedMs = 1L, peerId = "A")
        val accept = codec.decode(b.outbox.enqueued.single().payload) as SessionAcceptMessage
        a.engine.onInboundBytes(codec.encode(accept), receivedAtElapsedMs = 2L, peerId = "B")

        a.engine.send(peerId = "B", message = UserMessage("u1", "c1", 3L, payload = byteArrayOf(1, 2, 3)))
        val bytes = a.outbox.enqueued.single { it.messageId == "u1" }.payload
        val tampered = bytes.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        val r = b.engine.onInboundBytes(tampered, receivedAtElapsedMs = 4L, peerId = "A")
        val rejected = r as SessionAwareProtocolEngine.InboundOutcome.Rejected
        assertEquals("decrypt_failed", rejected.reason)
    }

    @Test
    fun handshake_algorithm_fallback_when_responder_does_not_support_initiator_preference() {
        val codec = BinaryProtocolCodec()

        data class Stack(val engine: SessionAwareProtocolEngine, val outbox: RecordingOutbox, val bundle: X3dhPreKeyBundle)

        fun makeEngine(support: SessionAeadSupport): Stack {
            val store = InMemoryConversationMessageStore()
            val recording = RecordingOutbox()
            val outbox = ConversationMessageStoreOutboxAdapter(recording, store)
            val ledger = InMemoryConversationDeliveryLedger()
            val inbound = InMemoryProtocolInboundPipeline(
                decoder = codec,
                encoder = codec,
                messageIdGenerator = IncrementingMessageIdGenerator(prefix = "ack", start = 1),
                ledger = ledger,
                messageStore = store,
            )

            val id = X3dhCrypto.generateIdentityKeyPair()
            val spk = X3dhCrypto.generateX25519KeyPair()
            val spkId = 1
            val spkSig = X3dhCrypto.signSignedPreKey(identitySeed = id.seed, signedPreKeyId = spkId, signedPreKeyPublic = spk.publicKey)
            val signed = X3dhSignedPreKey(preKeyId = spkId, privateKey = spk.privateKey, publicKey = spk.publicKey, signature = spkSig, createdAtElapsedMs = 0L)
            val preKeyStore = InMemoryX3dhPreKeyStore(signedPreKey = signed, oneTimePreKeys = emptyList())
            val bundle = preKeyStore.buildBundle(identityPublicKey = id.publicKey)

            val sessions = InMemorySessionStore(
                x3dhIdentitySeedEd = id.seed,
                x3dhIdentityPublicKeyEd = id.publicKey,
                x3dhPreKeyStore = preKeyStore,
            )
            val outbound = SessionBoundProtocolOutbound(outbox = outbox, encoder = codec, sessionStore = sessions)

            val handshakeHandler = SessionHandshakeHandler(
                sessionStore = sessions,
                encoder = codec,
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(8, 8) },
                aeadSupport = support,
            )
            val router = DefaultProtocolMessageRouter(
                encoder = codec,
                inbound = inbound,
                outbound = SessionBoundOutboundEnqueuer(outbound),
                handshakeHandler = handshakeHandler,
            )
            val engine = SessionAwareProtocolEngine(
                inbound = inbound,
                outbound = outbound,
                sessionStore = sessions,
                protocolDecoder = codec,
                protocolEncoder = codec,
                router = router,
                sessionIdGenerator = IncrementingSessionIdGenerator(prefix = "s", start = 1),
                localIdentityPublicKey = id.publicKey,
                responderNonceGenerator = { byteArrayOf(8, 8) },
                aeadSupport = support,
            )
            return Stack(engine, recording, bundle)
        }

        val a = makeEngine(support = InitiatorPrefersXChaCha)
        val b = makeEngine(support = AesOnlySupport)

        val init = a.engine.startSession(peerId = "B", conversationId = "c1", initiatorNonce = byteArrayOf(7, 7), peerBundle = b.bundle)
        assertEquals(SessionAeadAlgorithm.XCHACHA20_POLY1305, init.aeadAlgorithm)

        b.engine.onInboundBytes(codec.encode(init), receivedAtElapsedMs = 1L, peerId = "A")
        val accept = codec.decode(b.outbox.enqueued.single().payload) as SessionAcceptMessage

        // Responder should fall back to AES.
        assertEquals(SessionAeadAlgorithm.AES_256_GCM, accept.aeadAlgorithm)

        a.engine.onInboundBytes(codec.encode(accept), receivedAtElapsedMs = 2L, peerId = "B")

        // Should be able to send a session-bound message after fallback.
        val res = a.engine.send(peerId = "B", message = UserMessage("u1", "c1", 3L, payload = byteArrayOf(1)))
        assertEquals(EnqueueResult.Enqueued, res)
        assertFalse(a.outbox.enqueued.single { it.messageId == "u1" }.payload.contentEquals(codec.encode(UserMessage("u1", "c1", 3L, payload = byteArrayOf(1)))))
    }
}
