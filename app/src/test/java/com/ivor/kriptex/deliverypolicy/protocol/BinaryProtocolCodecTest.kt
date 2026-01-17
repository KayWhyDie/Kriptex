package com.ivor.kriptex.deliverypolicy.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryProtocolCodecTest {

    private val codec = BinaryProtocolCodec()

    @Test
    fun user_message_round_trip() {
        val msg = UserMessage(
            messageId = "u1",
            conversationId = "c1",
            createdAtElapsedMs = 123L,
            payload = byteArrayOf(1, 2, 3),
        )

        val bytes = codec.encode(msg)
        val decoded = codec.decode(bytes)

        val asUser = decoded as UserMessage
        assertEquals("u1", asUser.messageId)
        assertEquals("c1", asUser.conversationId)
        assertEquals(123L, asUser.createdAtElapsedMs)
        assertArrayEquals(byteArrayOf(1, 2, 3), asUser.payload)
    }

    @Test
    fun ack_message_round_trip() {
        val msg = AckMessage(
            messageId = "a1",
            conversationId = "c1",
            createdAtElapsedMs = 77L,
            ackedMessageId = "u1",
        )

        val bytes = codec.encode(msg)
        val decoded = codec.decode(bytes)

        val asAck = decoded as AckMessage
        assertEquals("a1", asAck.messageId)
        assertEquals("c1", asAck.conversationId)
        assertEquals(77L, asAck.createdAtElapsedMs)
        assertEquals("u1", asAck.ackedMessageId)
    }

    @Test
    fun session_init_round_trip() {
        val msg = SessionInitMessage(
            messageId = "si1",
            conversationId = "c1",
            createdAtElapsedMs = 5L,
            sessionId = "s1",
            aeadAlgorithm = SessionAeadAlgorithm.AES_256_GCM,
            initiatorIdentityPublicKey = byteArrayOf(1, 2),
            initiatorNonce = byteArrayOf(9, 9, 9),
            initiatorBasePublicKey = byteArrayOf(7, 7, 7),
            responderIdentityPublicKey = byteArrayOf(3, 4),
            responderSignedPreKeyId = 10,
            responderSignedPreKeyPublicKey = byteArrayOf(5, 6),
            responderSignedPreKeySignature = byteArrayOf(8, 8, 8, 8),
            responderOneTimePreKeyId = 11,
            responderOneTimePreKeyPublicKey = byteArrayOf(6, 6),
        )

        val bytes = codec.encode(msg)
        val decoded = codec.decode(bytes) as SessionInitMessage

        assertEquals("si1", decoded.messageId)
        assertEquals("c1", decoded.conversationId)
        assertEquals(5L, decoded.createdAtElapsedMs)
        assertEquals("s1", decoded.sessionId)
        assertEquals(SessionAeadAlgorithm.AES_256_GCM, decoded.aeadAlgorithm)
        assertArrayEquals(byteArrayOf(1, 2), decoded.initiatorIdentityPublicKey)
        assertArrayEquals(byteArrayOf(9, 9, 9), decoded.initiatorNonce)
        assertArrayEquals(byteArrayOf(7, 7, 7), decoded.initiatorBasePublicKey)
        assertArrayEquals(byteArrayOf(3, 4), decoded.responderIdentityPublicKey)
        assertEquals(10, decoded.responderSignedPreKeyId)
        assertArrayEquals(byteArrayOf(5, 6), decoded.responderSignedPreKeyPublicKey)
        assertArrayEquals(byteArrayOf(8, 8, 8, 8), decoded.responderSignedPreKeySignature)
        assertEquals(11, decoded.responderOneTimePreKeyId)
        assertArrayEquals(byteArrayOf(6, 6), decoded.responderOneTimePreKeyPublicKey)
    }

    @Test
    fun session_accept_round_trip() {
        val msg = SessionAcceptMessage(
            messageId = "sa1",
            conversationId = "c1",
            createdAtElapsedMs = 6L,
            sessionId = "s1",
            aeadAlgorithm = SessionAeadAlgorithm.AES_256_GCM,
            responderIdentityPublicKey = byteArrayOf(3, 4),
            responderNonce = byteArrayOf(8),
            initiatorIdentityPublicKey = byteArrayOf(1, 2),
            initiatorNonce = byteArrayOf(9, 9),
            initiatorBasePublicKey = byteArrayOf(7, 7, 7),
            responderSignedPreKeyId = 10,
            responderOneTimePreKeyId = 11,
            confirmTag = byteArrayOf(1, 1, 1, 1),
        )

        val bytes = codec.encode(msg)
        val decoded = codec.decode(bytes) as SessionAcceptMessage

        assertEquals("sa1", decoded.messageId)
        assertEquals("c1", decoded.conversationId)
        assertEquals(6L, decoded.createdAtElapsedMs)
        assertEquals("s1", decoded.sessionId)
        assertEquals(SessionAeadAlgorithm.AES_256_GCM, decoded.aeadAlgorithm)
        assertArrayEquals(byteArrayOf(3, 4), decoded.responderIdentityPublicKey)
        assertArrayEquals(byteArrayOf(8), decoded.responderNonce)
        assertArrayEquals(byteArrayOf(1, 2), decoded.initiatorIdentityPublicKey)
        assertArrayEquals(byteArrayOf(9, 9), decoded.initiatorNonce)
        assertArrayEquals(byteArrayOf(7, 7, 7), decoded.initiatorBasePublicKey)
        assertEquals(10, decoded.responderSignedPreKeyId)
        assertEquals(11, decoded.responderOneTimePreKeyId)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), decoded.confirmTag)
    }

    @Test
    fun sender_key_distribution_round_trip() {
        val msg = SenderKeyDistributionMessage(
            messageId = "skd1",
            conversationId = "c1",
            createdAtElapsedMs = 999L,
            groupId = ByteArray(32) { 7 },
            senderIdentityPublicKey = ByteArray(32) { 1 },
            senderKeyId = 42L,
            senderChainKey = ByteArray(32) { 9 },
        )

        val bytes = codec.encode(msg)
        val decoded = codec.decode(bytes) as SenderKeyDistributionMessage

        assertEquals("skd1", decoded.messageId)
        assertEquals("c1", decoded.conversationId)
        assertEquals(999L, decoded.createdAtElapsedMs)
        assertArrayEquals(ByteArray(32) { 7 }, decoded.groupId)
        assertArrayEquals(ByteArray(32) { 1 }, decoded.senderIdentityPublicKey)
        assertEquals(42L, decoded.senderKeyId)
        assertArrayEquals(ByteArray(32) { 9 }, decoded.senderChainKey)
    }

    @Test
    fun sender_key_group_message_round_trip() {
        val msg = SenderKeyGroupMessage(
            messageId = "skg1",
            conversationId = "g1",
            createdAtElapsedMs = 1000L,
            groupId = ByteArray(32) { 3 },
            senderIdentityPublicKey = ByteArray(32) { 4 },
            senderKeyId = 2L,
            counter = 9L,
            ciphertext = byteArrayOf(1, 2, 3, 4, 5),
        )

        val bytes = codec.encode(msg)
        val decoded = codec.decode(bytes) as SenderKeyGroupMessage

        assertEquals("skg1", decoded.messageId)
        assertEquals("g1", decoded.conversationId)
        assertEquals(1000L, decoded.createdAtElapsedMs)
        assertArrayEquals(ByteArray(32) { 3 }, decoded.groupId)
        assertArrayEquals(ByteArray(32) { 4 }, decoded.senderIdentityPublicKey)
        assertEquals(2L, decoded.senderKeyId)
        assertEquals(9L, decoded.counter)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), decoded.ciphertext)
    }
}
