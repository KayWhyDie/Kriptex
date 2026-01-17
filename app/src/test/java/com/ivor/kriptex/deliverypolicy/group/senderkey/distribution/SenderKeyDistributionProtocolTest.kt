package com.ivor.kriptex.deliverypolicy.group.senderkey.distribution

import com.ivor.kriptex.deliverypolicy.group.GroupDefinition
import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.group.InMemoryGroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.InMemorySenderKeyStore
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
import com.ivor.kriptex.deliverypolicy.protocol.SessionAcceptMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionInitMessage
import com.ivor.kriptex.deliverypolicy.session.InMemorySessionStore
import com.ivor.kriptex.deliverypolicy.session.IncrementingSessionIdGenerator
import com.ivor.kriptex.deliverypolicy.session.SessionAeadSupport
import com.ivor.kriptex.deliverypolicy.session.SessionAwareProtocolEngine
import com.ivor.kriptex.deliverypolicy.session.SessionBoundProtocolOutbound
import com.ivor.kriptex.deliverypolicy.session.SessionEnvelopeCodec
import com.ivor.kriptex.deliverypolicy.session.SessionEnvelope
import com.ivor.kriptex.deliverypolicy.session.x3dh.InMemoryX3dhPreKeyStore
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhCrypto
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhOneTimePreKey
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhPreKeyBundle
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhSignedPreKey
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.routing.DefaultProtocolMessageRouter
import com.ivor.kriptex.deliverypolicy.routing.adapters.SenderKeyDistributionEngineAdapter
import com.ivor.kriptex.deliverypolicy.routing.outbound.SessionBoundOutboundEnqueuer
import com.ivor.kriptex.deliverypolicy.session.routing.SessionHandshakeHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderKeyDistributionProtocolTest {

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
        val engine: SessionAwareProtocolEngine,
        val outbox: RecordingOutbox,
        val identityPublicKey: ByteArray,
        val peerBundle: X3dhPreKeyBundle,
        val groupStore: InMemoryGroupStore,
        val senderKeyStore: InMemorySenderKeyStore,
        val distStore: InMemorySenderKeyDistributionStore,
        val distEngine: SenderKeyDistributionEngine,
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
            engine = engine,
            outbox = recording,
            identityPublicKey = id.publicKey,
            peerBundle = bundle,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            distStore = distStore,
            distEngine = distEngine,
        )
    }

    private fun handshake(a: Stack, b: Stack, codec: BinaryProtocolCodec) {
        val init: SessionInitMessage = a.engine.startSession(
            peerId = "B",
            conversationId = "c1",
            initiatorNonce = byteArrayOf(7, 7),
            peerBundle = b.peerBundle,
        )
        b.engine.onInboundBytes(codec.encode(init), receivedAtElapsedMs = 1L, peerId = "A")

        val accept = codec.decode(b.outbox.enqueued.single().payload) as SessionAcceptMessage
        a.engine.onInboundBytes(codec.encode(accept), receivedAtElapsedMs = 2L, peerId = "B")

        // clear handshake outboxes for readability
        a.outbox.enqueued.clear()
        b.outbox.enqueued.clear()
    }

    @Test
    fun successful_distribution_and_ack_marks_delivered() {
        val codec = BinaryProtocolCodec()
        val a = makeStack()
        val b = makeStack()

        // Group membership includes both.
        val group = GroupDefinition(
            conversationId = "g1",
            memberIdentityPublicKeys = listOf(a.identityPublicKey, b.identityPublicKey),
        )
        a.groupStore.put(group)
        b.groupStore.put(group)

        handshake(a, b, codec)

        val groupId: GroupId = group.groupId

        val planned = a.distEngine.planDistributions(
            groupId = groupId,
            conversationIdForRecipient = { "c1" },
            messageIdGenerator = IncrementingMessageIdGenerator(prefix = "d", start = 1)::nextId,
        )
        assertEquals(1, planned.size)

        val distributionMsg: ProtocolMessage = planned.single().message
        a.engine.send(peerId = "B", message = distributionMsg)

        val bytesToB = a.outbox.enqueued.single().payload
        val inboundB = b.engine.onInboundBytes(bytesToB, receivedAtElapsedMs = 10L, peerId = "A")
        assertTrue(inboundB is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        // B should have stored sender key for A.
        val bHas = b.senderKeyStore.get(groupId, a.identityPublicKey)
        assertNotNull(bHas)

        // B should ACK it.
        val ackBytes = b.outbox.enqueued.single().payload
        assertTrue(SessionEnvelopeCodec().looksLikeEnvelope(ackBytes))

        val inboundA = a.engine.onInboundBytes(ackBytes, receivedAtElapsedMs = 11L, peerId = "B")
        assertTrue(inboundA is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        // A marks delivery for B.
        val state = a.distStore.getState(groupId, a.identityPublicKey)
        assertNotNull(state)
        assertTrue(state!!.deliveredRecipientIdentityPublicKeys.any { it.contentEquals(b.identityPublicKey) })
    }

    @Test
    fun duplicate_distribution_is_ignored() {
        val codec = BinaryProtocolCodec()
        val a = makeStack()
        val b = makeStack()

        val group = GroupDefinition(
            conversationId = "g1",
            memberIdentityPublicKeys = listOf(a.identityPublicKey, b.identityPublicKey),
        )
        a.groupStore.put(group)
        b.groupStore.put(group)

        handshake(a, b, codec)

        val groupId = group.groupId
        val planned = a.distEngine.planDistributions(
            groupId = groupId,
            conversationIdForRecipient = { "c1" },
            messageIdGenerator = { "d1" },
        )
        val msg = planned.single().message
        a.engine.send(peerId = "B", message = msg)

        val bytesToB = a.outbox.enqueued.single().payload
        val first = b.engine.onInboundBytes(bytesToB, receivedAtElapsedMs = 10L, peerId = "A")
        assertTrue(first is SessionAwareProtocolEngine.InboundOutcome.Accepted)

        // Replay the same bytes again: should be rejected as reused_message_id (session replay filter).
        val second = b.engine.onInboundBytes(bytesToB, receivedAtElapsedMs = 11L, peerId = "A")
        assertTrue(second is SessionAwareProtocolEngine.InboundOutcome.Rejected)

        val bHas = b.senderKeyStore.get(groupId, a.identityPublicKey)
        assertNotNull(bHas)
        assertEquals(msg.senderKeyId, bHas!!.senderKeyId)
    }

    @Test
    fun non_member_inbound_distribution_rejected() {
        val codec = BinaryProtocolCodec()
        val a = makeStack()
        val b = makeStack()

        // A thinks both are members, B thinks only B is a member.
        val groupA = GroupDefinition(
            conversationId = "g1",
            memberIdentityPublicKeys = listOf(a.identityPublicKey, b.identityPublicKey),
        )
        val groupB = GroupDefinition(
            conversationId = "g1",
            memberIdentityPublicKeys = listOf(b.identityPublicKey),
        )
        a.groupStore.put(groupA)
        b.groupStore.put(groupB)

        handshake(a, b, codec)

        val groupId = groupA.groupId
        val planned = a.distEngine.planDistributions(
            groupId = groupId,
            conversationIdForRecipient = { "c1" },
            messageIdGenerator = { "d1" },
        )
        val msg = planned.single().message
        a.engine.send(peerId = "B", message = msg)

        val bytesToB = a.outbox.enqueued.single().payload
        val inboundB = b.engine.onInboundBytes(bytesToB, receivedAtElapsedMs = 10L, peerId = "A")
        assertTrue(inboundB is SessionAwareProtocolEngine.InboundOutcome.Rejected)

        val bHas = b.senderKeyStore.get(groupId, a.identityPublicKey)
        assertNull(bHas)
    }

    @Test
    fun restore_is_safe_no_implicit_resend_and_idempotent_planning() {
        val a = makeStack()

        val group = GroupDefinition(
            conversationId = "g1",
            memberIdentityPublicKeys = listOf(a.identityPublicKey, ByteArray(32) { 2 }),
        )
        a.groupStore.put(group)

        val groupId = group.groupId

        val firstPlan = a.distEngine.planDistributions(
            groupId = groupId,
            conversationIdForRecipient = { "c1" },
            messageIdGenerator = { "d1" },
        )
        assertEquals(1, firstPlan.size)

        // Snapshot and restore.
        val snap = a.distStore.snapshot()
        val restoredStore = InMemorySenderKeyDistributionStore()
        restoredStore.restore(snap)

        val restoredEngine = SenderKeyDistributionEngine(
            localIdentityPublicKey = a.identityPublicKey,
            groupStore = a.groupStore,
            senderKeyStore = a.senderKeyStore,
            distributionStore = restoredStore,
        )

        // Planning again should NOT create a second distribution because one is pending.
        val secondPlan = restoredEngine.planDistributions(
            groupId = groupId,
            conversationIdForRecipient = { "c1" },
            messageIdGenerator = { "d2" },
        )
        assertEquals(0, secondPlan.size)
    }
}
