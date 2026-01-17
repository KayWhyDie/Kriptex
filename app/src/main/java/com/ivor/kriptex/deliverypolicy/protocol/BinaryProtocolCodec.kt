package com.ivor.kriptex.deliverypolicy.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Deterministic, dependency-free binary codec.
 *
 * Wire format (all integers big-endian):
 * - magic: 4 bytes "KPX1"
 * - version: u8 (currently 1)
 * - type: u8 (1=user, 2=ack, 3=unknown)
 * - messageId: len(u32) + utf8
 * - conversationId: len(u32) + utf8
 * - createdAtElapsedMs: i64
 * - type-specific:
 *   - user: payload len(u32) + payload
 *   - ack: ackedMessageId len(u32) + utf8
 *   - sender_key_distribution: groupId bytes, senderIdentity bytes, senderKeyId i64, senderChainKey bytes
 *   - sender_key_group_message: groupId bytes, senderIdentity bytes, senderKeyId i64, counter i64, ciphertext bytes
 *   - unknown: typeName len(u32)+utf8, payload len(u32)+payload
 */
class BinaryProtocolCodec : ProtocolEncoder, ProtocolDecoder {

    override fun encode(message: ProtocolMessage): ByteArray {
        val messageIdBytes = message.messageId.encodeToByteArray()
        val conversationIdBytes = message.conversationId.encodeToByteArray()

        val typeByte: Byte
        val extraSize: Int
        val writeExtra: (ByteBuffer) -> Unit

        when (message) {
            is UserMessage -> {
                typeByte = 1
                extraSize = 4 + message.payload.size
                writeExtra = { buf -> putBytes(buf, message.payload) }
            }

            is AckMessage -> {
                val ackedBytes = message.ackedMessageId.encodeToByteArray()
                typeByte = 2
                extraSize = 4 + ackedBytes.size
                writeExtra = { buf -> putUtf8(buf, ackedBytes) }
            }

            is SessionInitMessage -> {
                val sessionIdBytes = message.sessionId.encodeToByteArray()
                typeByte = 4
                extraSize = (4 + sessionIdBytes.size) +
                    1 +
                    (4 + message.initiatorIdentityPublicKey.size) +
                    (4 + message.initiatorNonce.size) +
                    (4 + message.initiatorBasePublicKey.size) +
                    (4 + message.responderIdentityPublicKey.size) +
                    4 +
                    (4 + message.responderSignedPreKeyPublicKey.size) +
                    (4 + message.responderSignedPreKeySignature.size) +
                    4 +
                    (4 + (message.responderOneTimePreKeyPublicKey?.size ?: 0))
                writeExtra = { buf ->
                    putUtf8(buf, sessionIdBytes)
                    buf.put(message.aeadAlgorithm.id.toByte())
                    putBytes(buf, message.initiatorIdentityPublicKey)
                    putBytes(buf, message.initiatorNonce)
                    putBytes(buf, message.initiatorBasePublicKey)
                    putBytes(buf, message.responderIdentityPublicKey)
                    buf.putInt(message.responderSignedPreKeyId)
                    putBytes(buf, message.responderSignedPreKeyPublicKey)
                    putBytes(buf, message.responderSignedPreKeySignature)
                    buf.putInt(message.responderOneTimePreKeyId ?: -1)
                    putBytes(buf, message.responderOneTimePreKeyPublicKey ?: byteArrayOf())
                }
            }

            is SessionAcceptMessage -> {
                val sessionIdBytes = message.sessionId.encodeToByteArray()
                typeByte = 5
                extraSize = (4 + sessionIdBytes.size) +
                    1 +
                    (4 + message.responderIdentityPublicKey.size) +
                    (4 + message.responderNonce.size) +
                    (4 + message.initiatorIdentityPublicKey.size) +
                    (4 + message.initiatorNonce.size) +
                    (4 + message.initiatorBasePublicKey.size) +
                    4 +
                    4 +
                    (4 + message.confirmTag.size)
                writeExtra = { buf ->
                    putUtf8(buf, sessionIdBytes)
                    buf.put(message.aeadAlgorithm.id.toByte())
                    putBytes(buf, message.responderIdentityPublicKey)
                    putBytes(buf, message.responderNonce)
                    putBytes(buf, message.initiatorIdentityPublicKey)
                    putBytes(buf, message.initiatorNonce)
                    putBytes(buf, message.initiatorBasePublicKey)
                    buf.putInt(message.responderSignedPreKeyId)
                    buf.putInt(message.responderOneTimePreKeyId ?: -1)
                    putBytes(buf, message.confirmTag)
                }
            }

            is SenderKeyDistributionMessage -> {
                typeByte = 6
                extraSize =
                    (4 + message.groupId.size) +
                    (4 + message.senderIdentityPublicKey.size) +
                    8 +
                    (4 + message.senderChainKey.size)
                writeExtra = { buf ->
                    putBytes(buf, message.groupId)
                    putBytes(buf, message.senderIdentityPublicKey)
                    buf.putLong(message.senderKeyId)
                    putBytes(buf, message.senderChainKey)
                }
            }

            is SenderKeyGroupMessage -> {
                typeByte = 7
                extraSize =
                    (4 + message.groupId.size) +
                    (4 + message.senderIdentityPublicKey.size) +
                    8 +
                    8 +
                    (4 + message.ciphertext.size)
                writeExtra = { buf ->
                    putBytes(buf, message.groupId)
                    putBytes(buf, message.senderIdentityPublicKey)
                    buf.putLong(message.senderKeyId)
                    buf.putLong(message.counter)
                    putBytes(buf, message.ciphertext)
                }
            }

            is UnknownMessage -> {
                val typeNameBytes = message.typeName.encodeToByteArray()
                typeByte = 3
                extraSize = (4 + typeNameBytes.size) + (4 + message.payload.size)
                writeExtra = { buf ->
                    putUtf8(buf, typeNameBytes)
                    putBytes(buf, message.payload)
                }
            }

            else -> {
                val typeNameBytes = message.type.name.encodeToByteArray()
                typeByte = 3
                extraSize = (4 + typeNameBytes.size) + 4
                writeExtra = { buf ->
                    putUtf8(buf, typeNameBytes)
                    putBytes(buf, byteArrayOf())
                }
            }
        }

        val headerSize = 4 + 1 + 1
        val baseSize = (4 + messageIdBytes.size) + (4 + conversationIdBytes.size) + 8
        val total = headerSize + baseSize + extraSize

        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(ProtocolVersion.CURRENT.toByte())
        buf.put(typeByte)
        putUtf8(buf, messageIdBytes)
        putUtf8(buf, conversationIdBytes)
        buf.putLong(message.createdAtElapsedMs)
        writeExtra(buf)

        return buf.array()
    }

    override fun decode(bytes: ByteArray): ProtocolMessage {
        if (bytes.size < 6) throw ProtocolWireFormatException("too_short")

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4)
        buf.get(magic)
        if (!magic.contentEquals(MAGIC)) throw ProtocolWireFormatException("bad_magic")

        val version = buf.get()
        if (version != ProtocolVersion.CURRENT.toByte()) throw ProtocolWireFormatException("unsupported_version=$version")

        val type = buf.get().toInt() and 0xFF
        val messageId = readUtf8(buf)
        val conversationId = readUtf8(buf)
        val createdAt = readLong(buf)

        return when (type) {
            1 -> {
                val payload = readBytes(buf)
                UserMessage(messageId, conversationId, createdAt, payload)
            }

            2 -> {
                val acked = readUtf8(buf)
                AckMessage(messageId, conversationId, createdAt, acked)
            }

            4 -> {
                val sessionId = readUtf8(buf)
                if (buf.remaining() < 1) throw ProtocolWireFormatException("truncated_aead")
                val algo = SessionAeadAlgorithm.fromId(buf.get().toInt() and 0xFF)
                val initiatorPub = readBytes(buf)
                val initiatorNonce = readBytes(buf)
                val initiatorBasePub = readBytes(buf)
                val responderIdentityPub = readBytes(buf)
                if (buf.remaining() < 4) throw ProtocolWireFormatException("truncated_spk_id")
                val responderSignedPreKeyId = buf.int
                val responderSignedPreKeyPub = readBytes(buf)
                val responderSignedPreKeySig = readBytes(buf)
                if (buf.remaining() < 4) throw ProtocolWireFormatException("truncated_opk_id")
                val responderOneTimePreKeyIdRaw = buf.int
                val responderOneTimePreKeyPub = readBytes(buf)
                SessionInitMessage(
                    messageId = messageId,
                    conversationId = conversationId,
                    createdAtElapsedMs = createdAt,
                    sessionId = sessionId,
                    aeadAlgorithm = algo,
                    initiatorIdentityPublicKey = initiatorPub,
                    initiatorNonce = initiatorNonce,
                    initiatorBasePublicKey = initiatorBasePub,
                    responderIdentityPublicKey = responderIdentityPub,
                    responderSignedPreKeyId = responderSignedPreKeyId,
                    responderSignedPreKeyPublicKey = responderSignedPreKeyPub,
                    responderSignedPreKeySignature = responderSignedPreKeySig,
                    responderOneTimePreKeyId = responderOneTimePreKeyIdRaw.takeIf { it >= 0 },
                    responderOneTimePreKeyPublicKey = responderOneTimePreKeyPub.takeIf { responderOneTimePreKeyIdRaw >= 0 },
                )
            }

            5 -> {
                val sessionId = readUtf8(buf)
                if (buf.remaining() < 1) throw ProtocolWireFormatException("truncated_aead")
                val algo = SessionAeadAlgorithm.fromId(buf.get().toInt() and 0xFF)
                val responderPub = readBytes(buf)
                val responderNonce = readBytes(buf)
                val initiatorPub = readBytes(buf)
                val initiatorNonce = readBytes(buf)
                val initiatorBasePub = readBytes(buf)
                if (buf.remaining() < 4) throw ProtocolWireFormatException("truncated_spk_id")
                val responderSignedPreKeyId = buf.int
                if (buf.remaining() < 4) throw ProtocolWireFormatException("truncated_opk_id")
                val responderOneTimePreKeyIdRaw = buf.int
                val confirmTag = readBytes(buf)
                SessionAcceptMessage(
                    messageId = messageId,
                    conversationId = conversationId,
                    createdAtElapsedMs = createdAt,
                    sessionId = sessionId,
                    aeadAlgorithm = algo,
                    responderIdentityPublicKey = responderPub,
                    responderNonce = responderNonce,
                    initiatorIdentityPublicKey = initiatorPub,
                    initiatorNonce = initiatorNonce,
                    initiatorBasePublicKey = initiatorBasePub,
                    responderSignedPreKeyId = responderSignedPreKeyId,
                    responderOneTimePreKeyId = responderOneTimePreKeyIdRaw.takeIf { it >= 0 },
                    confirmTag = confirmTag,
                )
            }

            6 -> {
                val groupId = readBytes(buf)
                val senderIdentity = readBytes(buf)
                val senderKeyId = readLong(buf)
                val senderChainKey = readBytes(buf)
                SenderKeyDistributionMessage(
                    messageId = messageId,
                    conversationId = conversationId,
                    createdAtElapsedMs = createdAt,
                    groupId = groupId,
                    senderIdentityPublicKey = senderIdentity,
                    senderKeyId = senderKeyId,
                    senderChainKey = senderChainKey,
                )
            }

            7 -> {
                val groupId = readBytes(buf)
                val senderIdentity = readBytes(buf)
                val senderKeyId = readLong(buf)
                val counter = readLong(buf)
                val ciphertext = readBytes(buf)
                SenderKeyGroupMessage(
                    messageId = messageId,
                    conversationId = conversationId,
                    createdAtElapsedMs = createdAt,
                    groupId = groupId,
                    senderIdentityPublicKey = senderIdentity,
                    senderKeyId = senderKeyId,
                    counter = counter,
                    ciphertext = ciphertext,
                )
            }

            3 -> {
                val typeName = readUtf8(buf)
                val payload = readBytes(buf)
                UnknownMessage(messageId, conversationId, createdAt, typeName, payload)
            }

            else -> {
                // Preserve remaining bytes as payload for forward-compat.
                val remaining = ByteArray(max(0, buf.remaining()))
                buf.get(remaining)
                UnknownMessage(
                    messageId = messageId,
                    conversationId = conversationId,
                    createdAtElapsedMs = createdAt,
                    typeName = "type_$type",
                    payload = remaining,
                )
            }
        }
    }

    private fun putUtf8(buf: ByteBuffer, bytes: ByteArray) {
        buf.putInt(bytes.size)
        buf.put(bytes)
    }

    private fun putBytes(buf: ByteBuffer, bytes: ByteArray) {
        buf.putInt(bytes.size)
        buf.put(bytes)
    }

    private fun readUtf8(buf: ByteBuffer): String {
        val len = readLen(buf)
        if (len == 0) return ""
        val b = ByteArray(len)
        buf.get(b)
        return b.decodeToString()
    }

    private fun readBytes(buf: ByteBuffer): ByteArray {
        val len = readLen(buf)
        if (len == 0) return byteArrayOf()
        val b = ByteArray(len)
        buf.get(b)
        return b
    }

    private fun readLen(buf: ByteBuffer): Int {
        if (buf.remaining() < 4) throw ProtocolWireFormatException("truncated_len")
        val len = buf.int
        if (len < 0) throw ProtocolWireFormatException("negative_len")
        if (buf.remaining() < len) throw ProtocolWireFormatException("truncated_payload")
        return len
    }

    private fun readLong(buf: ByteBuffer): Long {
        if (buf.remaining() < 8) throw ProtocolWireFormatException("truncated_long")
        return buf.long
    }

    private companion object {
        private val MAGIC = byteArrayOf('K'.code.toByte(), 'P'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte())
    }
}
