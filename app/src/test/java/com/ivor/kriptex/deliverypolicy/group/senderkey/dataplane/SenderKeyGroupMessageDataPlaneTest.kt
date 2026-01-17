package com.ivor.kriptex.deliverypolicy.group.senderkey.dataplane

import com.ivor.kriptex.deliverypolicy.group.GroupDefinition
import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.group.InMemoryGroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.InMemorySenderKeyStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.distribution.InMemorySenderKeyDistributionStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.distribution.SenderKeyDistributionEngine
import com.ivor.kriptex.deliverypolicy.ledger.InMemoryConversationDeliveryLedger
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
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyGroupMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionAcceptMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.session.InMemorySessionStore
import com.ivor.kriptex.deliverypolicy.session.IncrementingSessionIdGenerator
import com.ivor.kriptex.deliverypolicy.session.SessionAeadSupport
import com.ivor.kriptex.deliverypolicy.session.SessionAwareProtocolEngine
import com.ivor.kriptex.deliverypolicy.session.SessionBoundProtocolOutbound
import com.ivor.kriptex.deliverypolicy.session.x3dh.InMemoryX3dhPreKeyStore
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhCrypto
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhOneTimePreKey
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhPreKeyBundle
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhSignedPreKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.ivor.kriptex.deliverypolicy.routing.DefaultProtocolMessageRouter
import com.ivor.kriptex.deliverypolicy.routing.adapters.SenderKeyDistributionEngineAdapter
import com.ivor.kriptex.deliverypolicy.routing.adapters.SenderKeyGroupMessageEngineAdapter
import com.ivor.kriptex.deliverypolicy.routing.outbound.SessionBoundOutboundEnqueuer
import com.ivor.kriptex.deliverypolicy.session.routing.SessionHandshakeHandler

class SenderKeyGroupMessageDataPlaneTest {

    private object AesOnlySupport : SessionAeadSupport {
        override fun preferred(): SessionAeadAlgorithm = SessionAeadAlgorithm.AES_256_GCM
        override fun supports(algorithm: SessionAeadAlgorithm): Boolean = algorithm == SessionAeadAlgorithm.AES_256_GCM
    }

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

    private data class Stack(
        val codec: BinaryProtocolCodec,
        val engine: SessionAwareProtocolEngine,
        val outbox: RecordingOutbox,
        val store: InMemoryConversationMessageStore,
        val identityPublicKey: ByteArray,
        val peerBundle: X3dhPreKeyBundle,
        val groupStore: InMemoryGroupStore,
        val senderKeyStore: InMemorySenderKeyStore,
        val distEngine: SenderKeyDistributionEngine,
        val groupEngine: SenderKeyGroupMessageEngine,
    )

    private fun makeStack(): Stack {
        val codec = BinaryProtocolCodec()
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

        val groupStore = InMemoryGroupStore()
        val senderKeyStore = InMemorySenderKeyStore()
        val distStore = InMemorySenderKeyDistributionStore()
        val distEngine = SenderKeyDistributionEngine(
            localIdentityPublicKey = id.publicKey,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            distributionStore = distStore,
        )

        val groupEngine = SenderKeyGroupMessageEngine(
            localIdentityPublicKey = id.publicKey,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
        )

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
            outbound = SessionBoundOutboundEnqueuer(sessionOutbound),
            handshakeHandler = handshakeHandler,
            senderKeyDistributionHandler = SenderKeyDistributionEngineAdapter(distEngine),
            senderKeyGroupMessageHandler = SenderKeyGroupMessageEngineAdapter(groupEngine),
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
            aeadSupport = AesOnlySupport,
        )

        return Stack(
            codec = codec,
            engine = engine,
            outbox = recording,
            store = store,
            identityPublicKey = id.publicKey,
            peerBundle = bundle,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            distEngine = distEngine,
            groupEngine = groupEngine,
        )
    }

    private fun handshake(a: Stack, b: Stack, conversationId: String) {
        val init = a.engine.startSession(
            peerId = "B",
            conversationId = conversationId,
            initiatorNonce = byteArrayOf(7, 7),
            peerBundle = b.peerBundle,
        )
        b.engine.onInboundBytes(a.codec.encode(init), receivedAtElapsedMs = 1L, peerId = "A")

        val accept = a.codec.decode(b.outbox.enqueued.single().payload) as SessionAcceptMessage
        a.engine.onInboundBytes(a.codec.encode(accept), receivedAtElapsedMs = 2L, peerId = "B")

        a.outbox.enqueued.clear()
        b.outbox.enqueued.clear()
    }

    private fun ensureDistribution(from: Stack, to: Stack, conversationId: String, groupId: GroupId) {
        val planned = from.distEngine.planDistributions(
            groupId = groupId,
            conversationIdForRecipient = { conversationId },
            messageIdGenerator = IncrementingMessageIdGenerator(prefix = "d", start = 1)::nextId,
        )
        val msg: ProtocolMessage = planned.single().message
        from.engine.send(peerId = "B", message = msg)

        val bytesToB = from.outbox.enqueued.single().payload
        val inboundB = to.engine.onInboundBytes(bytesToB, receivedAtElapsedMs = 10L, peerId = "A")
        assertTrue(inboundB is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        // Deliver ACK back (optional but keeps state tidy).
        val ackBytes = to.outbox.enqueued.single().payload
        val inboundA = from.engine.onInboundBytes(ackBytes, receivedAtElapsedMs = 11L, peerId = "B")
        assertTrue(inboundA is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        from.outbox.enqueued.clear()
        to.outbox.enqueued.clear()
    }

    @Test
    fun encrypt_decrypt_round_trip() {
        val a = makeStack()
        val b = makeStack()

        val group = GroupDefinition(
            conversationId = "g1",
            memberIdentityPublicKeys = listOf(a.identityPublicKey, b.identityPublicKey),
        )
        a.groupStore.put(group)
        b.groupStore.put(group)

        handshake(a, b, conversationId = "g1")
        ensureDistribution(from = a, to = b, conversationId = "g1", groupId = group.groupId)

        val outbound = a.groupEngine.encryptOutbound(
            conversationId = "g1",
            messageId = "m1",
            createdAtElapsedMs = 100L,
            plaintextPayload = byteArrayOf(1, 2, 3),
        )

        val bytesToB = a.engine.wrapForSession(peerId = "B", message = outbound)
        val inboundB = b.engine.onInboundBytes(bytesToB, receivedAtElapsedMs = 20L, peerId = "A")
        assertTrue(inboundB is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        val stored = b.store.message("m1")
        assertNotNull(stored)
        val decoded = b.codec.decode(stored!!.payload)
        val asUser = decoded as com.ivor.kriptex.deliverypolicy.protocol.UserMessage
        assertArrayEquals(byteArrayOf(1, 2, 3), asUser.payload)
    }

    @Test
    fun replay_rejected_even_with_new_message_id() {
        val a = makeStack()
        val b = makeStack()

        val group = GroupDefinition(
            conversationId = "g1",
            memberIdentityPublicKeys = listOf(a.identityPublicKey, b.identityPublicKey),
        )
        a.groupStore.put(group)
        b.groupStore.put(group)

        handshake(a, b, conversationId = "g1")
        ensureDistribution(from = a, to = b, conversationId = "g1", groupId = group.groupId)

        val msg1 = a.groupEngine.encryptOutbound(
            conversationId = "g1",
            messageId = "m1",
            createdAtElapsedMs = 100L,
            plaintextPayload = byteArrayOf(9),
        )
        val bytes1 = a.engine.wrapForSession(peerId = "B", message = msg1)
        val inbound1 = b.engine.onInboundBytes(bytes1, receivedAtElapsedMs = 20L, peerId = "A")
        assertTrue(inbound1 is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        // Replay same sender-key ciphertext+counter but with a new protocol messageId.
        val replay: SenderKeyGroupMessage = msg1.copy(messageId = "m2")
        val replayBytes = a.engine.wrapForSession(peerId = "B", message = replay)
        val inbound2 = b.engine.onInboundBytes(replayBytes, receivedAtElapsedMs = 21L, peerId = "A")
        assertTrue(inbound2 is SessionAwareProtocolEngine.InboundOutcome.Rejected)
        assertEquals("replay", (inbound2 as SessionAwareProtocolEngine.InboundOutcome.Rejected).reason)
    }

    @Test
    fun out_of_order_accepts_within_window_and_survives_restore() {
        val a = makeStack()
        val b = makeStack()

        val group = GroupDefinition(
            conversationId = "g1",
            memberIdentityPublicKeys = listOf(a.identityPublicKey, b.identityPublicKey),
        )
        a.groupStore.put(group)
        b.groupStore.put(group)

        handshake(a, b, conversationId = "g1")
        ensureDistribution(from = a, to = b, conversationId = "g1", groupId = group.groupId)

        val msg1 = a.groupEngine.encryptOutbound(
            conversationId = "g1",
            messageId = "m1",
            createdAtElapsedMs = 100L,
            plaintextPayload = byteArrayOf(1),
        )
        val bytes1 = a.engine.wrapForSession(peerId = "B", message = msg1)

        val msg2 = a.groupEngine.encryptOutbound(
            conversationId = "g1",
            messageId = "m2",
            createdAtElapsedMs = 101L,
            plaintextPayload = byteArrayOf(2),
        )
        val bytes2 = a.engine.wrapForSession(peerId = "B", message = msg2)

        // Deliver out-of-order: 2 then 1.
        val inbound2 = b.engine.onInboundBytes(bytes2, receivedAtElapsedMs = 200L, peerId = "A")
        assertTrue(inbound2 is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        // Simulate restart of sender-key store.
        val snap = b.senderKeyStore.snapshot()
        b.senderKeyStore.restore(snap)

        val inbound1 = b.engine.onInboundBytes(bytes1, receivedAtElapsedMs = 201L, peerId = "A")
        assertTrue(inbound1 is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        val stored1 = b.store.message("m1")
        val stored2 = b.store.message("m2")
        assertNotNull(stored1)
        assertNotNull(stored2)

        val p1 = (b.codec.decode(stored1!!.payload) as com.ivor.kriptex.deliverypolicy.protocol.UserMessage).payload
        val p2 = (b.codec.decode(stored2!!.payload) as com.ivor.kriptex.deliverypolicy.protocol.UserMessage).payload
        assertArrayEquals(byteArrayOf(1), p1)
        assertArrayEquals(byteArrayOf(2), p2)

        val st = b.senderKeyStore.get(group.groupId, a.identityPublicKey)
        assertNotNull(st)
        assertEquals(3L, st!!.nextCounter)
        assertTrue(st.skippedMessageKeys.isEmpty())
    }
}
