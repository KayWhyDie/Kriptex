package com.ivor.kriptex.deliverypolicy.routing

import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult
import com.ivor.kriptex.deliverypolicy.protocol.AckMessage
import com.ivor.kriptex.deliverypolicy.protocol.BinaryProtocolCodec
import com.ivor.kriptex.deliverypolicy.protocol.GroupMediaKeyDistributionMessage
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolInboundPipeline
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolInboundResult
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyDistributionMessage
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyGroupMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionAcceptMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.protocol.SessionInitMessage
import com.ivor.kriptex.deliverypolicy.protocol.UnknownMessage
import com.ivor.kriptex.deliverypolicy.protocol.UserMessage
import com.ivor.kriptex.deliverypolicy.routing.outbound.ProtocolOutboundEnqueuer
import com.ivor.kriptex.deliverypolicy.routing.handlers.GroupMediaKeyDistributionHandler
import com.ivor.kriptex.deliverypolicy.routing.handlers.SenderKeyDistributionHandler
import com.ivor.kriptex.deliverypolicy.routing.handlers.SenderKeyGroupMessageHandler
import com.ivor.kriptex.deliverypolicy.persistence.PersistedProtocolInboundPipelineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolMessageRouterTest {

    private class FakeInbound(private val encoder: BinaryProtocolCodec) : ProtocolInboundPipeline {
        var called = 0
        var drained = 0
        private val pending = ArrayList<ProtocolMessage>()

        fun setPending(vararg msgs: ProtocolMessage) {
            pending.clear()
            pending.addAll(msgs)
        }

        override fun onInboundBytes(bytes: ByteArray, receivedAtElapsedMs: Long, senderId: String): ProtocolInboundResult {
            called++
            val decoded = encoder.decode(bytes)
            return ProtocolInboundResult.Accepted(decoded.messageId, decoded.conversationId, receiveIndex = 0, type = decoded.type)
        }

        override fun drainPendingOutbound(): List<ProtocolMessage> {
            drained++
            val out = pending.toList()
            pending.clear()
            return out
        }

        override fun snapshot(): PersistedProtocolInboundPipelineSnapshot = throw UnsupportedOperationException()
        override fun restore(snapshot: PersistedProtocolInboundPipelineSnapshot) = throw UnsupportedOperationException()
    }

    private class FakeOutbound : ProtocolOutboundEnqueuer {
        val enqueued = ArrayList<ProtocolMessage>()
        override fun enqueue(peerId: String, message: ProtocolMessage): EnqueueResult {
            enqueued.add(message)
            return EnqueueResult.Enqueued
        }
    }

    private class FakeHandshakeHandler(private val encoder: BinaryProtocolCodec) : ProtocolHandshakeHandler {
        var called = 0
        override fun handleHandshake(message: ProtocolMessage, context: RoutingContext): HandshakeResult {
            called++
            val bytes = encoder.encode(message)
            val outbound = if (message is SessionInitMessage) {
                SessionAcceptMessage(
                    messageId = message.messageId + ":accept",
                    conversationId = message.conversationId,
                    createdAtElapsedMs = 1L,
                    sessionId = message.sessionId,
                    aeadAlgorithm = message.aeadAlgorithm,
                    responderIdentityPublicKey = ByteArray(32) { 2 },
                    responderNonce = byteArrayOf(9),
                    initiatorIdentityPublicKey = message.initiatorIdentityPublicKey,
                    initiatorNonce = message.initiatorNonce,
                    initiatorBasePublicKey = message.initiatorBasePublicKey,
                    responderSignedPreKeyId = message.responderSignedPreKeyId,
                    responderOneTimePreKeyId = message.responderOneTimePreKeyId,
                    confirmTag = byteArrayOf(1, 1, 1, 1),
                )
            } else {
                null
            }
            return HandshakeResult.Accepted(inboundBytesToStore = bytes, outboundToSend = outbound)
        }
    }

    private class FakeDistributionHandler : SenderKeyDistributionHandler {
        var applyCalled = 0
        var ackCalled = 0

        override fun applyInboundDistribution(authenticatedPeerIdentityPublicKey: ByteArray, msg: SenderKeyDistributionMessage): SenderKeyDistributionHandler.InboundApplyResult {
            applyCalled++
            return SenderKeyDistributionHandler.InboundApplyResult(accepted = true)
        }

        override fun onInboundAck(authenticatedPeerIdentityPublicKey: ByteArray, ackedMessageId: String) {
            ackCalled++
        }
    }

    private class FakeGroupHandler : SenderKeyGroupMessageHandler {
        var decryptCalled = 0

        override fun decryptInbound(authenticatedPeerIdentityPublicKey: ByteArray, msg: SenderKeyGroupMessage): SenderKeyGroupMessageHandler.InboundDecision {
            decryptCalled++
            return SenderKeyGroupMessageHandler.InboundDecision.Accepted(
                userMessage = UserMessage(
                    messageId = msg.messageId,
                    conversationId = msg.conversationId,
                    createdAtElapsedMs = msg.createdAtElapsedMs,
                    payload = byteArrayOf(1),
                ),
            )
        }
    }

    private class FakeGroupMediaDistributionHandler : GroupMediaKeyDistributionHandler {
        var applyCalled = 0

        override fun applyInboundGroupMediaKeyDistribution(
            authenticatedPeerIdentityPublicKey: ByteArray,
            msg: GroupMediaKeyDistributionMessage,
        ): GroupMediaKeyDistributionHandler.InboundApplyResult {
            applyCalled++
            return GroupMediaKeyDistributionHandler.InboundApplyResult(accepted = true)
        }
    }

    private fun ctx(session: Boolean, restore: Boolean, authenticated: Boolean = true) = RoutingContext(
        peerId = "P",
        authenticatedPeerIdentityPublicKey = if (authenticated) ByteArray(32) { 1 } else null,
        isSessionEnveloped = session,
        isRestore = restore,
        receivedAtElapsedMs = 10L,
        senderId = "P",
    )

    @Test
    fun routes_each_known_type_to_expected_target() {
        val codec = BinaryProtocolCodec()
        val inbound = FakeInbound(codec)
        val outbound = FakeOutbound()
        val handshake = FakeHandshakeHandler(codec)
        val dist = FakeDistributionHandler()
        val group = FakeGroupHandler()
        val gmk = FakeGroupMediaDistributionHandler()

        val router = DefaultProtocolMessageRouter(
            encoder = codec,
            inbound = inbound,
            outbound = outbound,
            handshakeHandler = handshake,
            senderKeyDistributionHandler = dist,
            senderKeyGroupMessageHandler = group,
            groupMediaKeyDistributionHandler = gmk,
        )

        val user = UserMessage("u1", "c1", 1L, byteArrayOf(9))
        inbound.setPending(AckMessage("a1", "c1", 2L, ackedMessageId = "u1"))
        val rUser = router.route(user, ctx(session = true, restore = true))
        assertTrue(rUser is RoutingResult.Accepted)
        assertEquals(ProtocolMessageKind.ONE_TO_ONE_USER, rUser.kind)

        val ack = AckMessage("a2", "c1", 3L, ackedMessageId = "x")
        val rAck = router.route(ack, ctx(session = true, restore = true))
        assertTrue(rAck is RoutingResult.Accepted)
        assertEquals(ProtocolMessageKind.ACK, rAck.kind)

        val skd = SenderKeyDistributionMessage("d1", "c1", 4L, ByteArray(32) { 1 }, ByteArray(32) { 1 }, 1L, ByteArray(32) { 2 })
        val rSkd = router.route(skd, ctx(session = true, restore = true))
        assertTrue(rSkd is RoutingResult.Accepted)
        assertEquals(ProtocolMessageKind.SENDER_KEY_DISTRIBUTION, rSkd.kind)
        assertEquals(1, dist.applyCalled)

        val skg = SenderKeyGroupMessage("gmsg1", "g1", 5L, ByteArray(32) { 3 }, ByteArray(32) { 1 }, 1L, 1L, byteArrayOf(7, 7))
        val rSkg = router.route(skg, ctx(session = true, restore = true))
        assertTrue(rSkg is RoutingResult.Accepted)
        assertEquals(ProtocolMessageKind.GROUP_MESSAGE, rSkg.kind)
        assertEquals(1, group.decryptCalled)

        val gmkd = GroupMediaKeyDistributionMessage(
            messageId = "gmkd1",
            conversationId = "g1",
            createdAtElapsedMs = 6L,
            groupId = ByteArray(32) { 3 },
            senderIdentityPublicKey = ByteArray(32) { 1 },
            senderKeyId = 1L,
            counter = 1L,
            mediaId = "m1",
            ciphertext = byteArrayOf(8, 8),
        )
        val rGmkd = router.route(gmkd, ctx(session = true, restore = true))
        assertTrue(rGmkd is RoutingResult.Accepted)
        assertEquals(ProtocolMessageKind.GROUP_MEDIA_KEY_DISTRIBUTION, rGmkd.kind)
        assertEquals(1, gmk.applyCalled)

        val init = SessionInitMessage(
            messageId = "si1",
            conversationId = "c1",
            createdAtElapsedMs = 1L,
            sessionId = "s1",
            aeadAlgorithm = SessionAeadAlgorithm.AES_256_GCM,
            initiatorIdentityPublicKey = ByteArray(32) { 1 },
            initiatorNonce = byteArrayOf(1),
            initiatorBasePublicKey = ByteArray(32) { 9 },
            responderIdentityPublicKey = ByteArray(32) { 2 },
            responderSignedPreKeyId = 1,
            responderSignedPreKeyPublicKey = ByteArray(32) { 3 },
            responderSignedPreKeySignature = byteArrayOf(4),
            responderOneTimePreKeyId = null,
            responderOneTimePreKeyPublicKey = null,
        )
        val rInit = router.route(init, ctx(session = false, restore = true, authenticated = false))
        assertTrue(rInit is RoutingResult.Accepted)
        assertEquals(ProtocolMessageKind.HANDSHAKE, rInit.kind)
        assertEquals(1, handshake.called)
    }

    @Test
    fun unknown_type_is_rejected() {
        val codec = BinaryProtocolCodec()
        val inbound = FakeInbound(codec)
        val outbound = FakeOutbound()
        val handshake = FakeHandshakeHandler(codec)
        val router = DefaultProtocolMessageRouter(codec, inbound, outbound, handshake)

        val unknown = UnknownMessage("x1", "c1", 1L, typeName = "weird", payload = byteArrayOf(1, 2))
        val r = router.route(unknown, ctx(session = true, restore = true))
        assertTrue(r is RoutingResult.Rejected)
        assertEquals(ProtocolMessageKind.UNKNOWN, r.kind)
        assertEquals("unknown_message_type", (r as RoutingResult.Rejected).reason)
    }

    @Test
    fun mismatched_routing_is_rejected() {
        val codec = BinaryProtocolCodec()
        val inbound = FakeInbound(codec)
        val outbound = FakeOutbound()
        val handshake = FakeHandshakeHandler(codec)
        val router = DefaultProtocolMessageRouter(codec, inbound, outbound, handshake)

        val init = SessionAcceptMessage(
            messageId = "sa1",
            conversationId = "c1",
            createdAtElapsedMs = 1L,
            sessionId = "s1",
            aeadAlgorithm = SessionAeadAlgorithm.AES_256_GCM,
            responderIdentityPublicKey = ByteArray(32) { 2 },
            responderNonce = byteArrayOf(9),
            initiatorIdentityPublicKey = ByteArray(32) { 1 },
            initiatorNonce = byteArrayOf(1),
            initiatorBasePublicKey = ByteArray(32) { 9 },
            responderSignedPreKeyId = 1,
            responderOneTimePreKeyId = null,
            confirmTag = byteArrayOf(1, 1, 1, 1),
        )

        val r = router.route(init, ctx(session = true, restore = true, authenticated = true))
        assertTrue(r is RoutingResult.Rejected)
        assertEquals("handshake_must_be_raw", (r as RoutingResult.Rejected).reason)
    }

    @Test
    fun restore_and_live_have_equivalent_classification_and_target() {
        val codec = BinaryProtocolCodec()
        val inbound = FakeInbound(codec)
        val outbound = FakeOutbound()
        val handshake = FakeHandshakeHandler(codec)
        val router = DefaultProtocolMessageRouter(codec, inbound, outbound, handshake)

        val user = UserMessage("u1", "c1", 1L, byteArrayOf(9))
        inbound.setPending(AckMessage("a1", "c1", 2L, ackedMessageId = "u1"))

        val restoreRes = router.route(user, ctx(session = true, restore = true)) as RoutingResult.Accepted
        inbound.setPending(AckMessage("a1", "c1", 2L, ackedMessageId = "u1"))
        val liveRes = router.route(user, ctx(session = true, restore = false)) as RoutingResult.Accepted

        assertEquals(restoreRes.kind, liveRes.kind)
        assertEquals(restoreRes.target, liveRes.target)
        assertTrue(restoreRes.enqueuedOutbound.isEmpty())
        assertTrue(liveRes.enqueuedOutbound.isNotEmpty())
    }
}
