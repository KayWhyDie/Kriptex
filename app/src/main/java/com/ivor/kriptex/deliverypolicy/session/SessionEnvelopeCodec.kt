package com.ivor.kriptex.deliverypolicy.session

import java.nio.ByteBuffer
import java.nio.ByteOrder

class SessionEnvelopeCodec {

    fun encode(envelope: SessionEnvelope): ByteArray {
        val sessionIdBytes = envelope.sessionId.encodeToByteArray()

        return if (envelope.messageId.isNullOrEmpty()) {
            // Legacy v1: plaintext inner.
            val total = 4 + 1 + (4 + sessionIdBytes.size) + 8 + (4 + envelope.inner.size)
            val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            buf.put(MAGIC)
            buf.put(VERSION_V1)
            putUtf8(buf, sessionIdBytes)
            buf.putLong(envelope.seq)
            putBytes(buf, envelope.inner)
            buf.array()
        } else {
            // v2: includes cleartext messageId to bind AEAD AAD.
            val messageIdBytes = envelope.messageId.encodeToByteArray()
            val total = 4 + 1 + (4 + sessionIdBytes.size) + 8 + (4 + messageIdBytes.size) + (4 + envelope.inner.size)
            val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            buf.put(MAGIC)
            buf.put(VERSION_V2)
            putUtf8(buf, sessionIdBytes)
            buf.putLong(envelope.seq)
            putUtf8(buf, messageIdBytes)
            putBytes(buf, envelope.inner)
            buf.array()
        }
    }

    fun decode(bytes: ByteArray): SessionEnvelope {
        if (bytes.size < 5) throw SessionWireFormatException("too_short")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4)
        buf.get(magic)
        if (!magic.contentEquals(MAGIC)) throw SessionWireFormatException("bad_magic")
        val ver = buf.get()
        if (ver != VERSION_V1 && ver != VERSION_V2) throw SessionWireFormatException("unsupported_version=$ver")

        val sessionId = readUtf8(buf)
        if (buf.remaining() < 8) throw SessionWireFormatException("truncated_seq")
        val seq = buf.long

        return if (ver == VERSION_V1) {
            val inner = readBytes(buf)
            SessionEnvelope(sessionId = sessionId, seq = seq, messageId = null, inner = inner)
        } else {
            val messageId = readUtf8(buf)
            val inner = readBytes(buf)
            SessionEnvelope(sessionId = sessionId, seq = seq, messageId = messageId, inner = inner)
        }
    }

    fun looksLikeEnvelope(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        return bytes[0] == MAGIC[0] && bytes[1] == MAGIC[1] && bytes[2] == MAGIC[2] && bytes[3] == MAGIC[3]
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
        if (buf.remaining() < 4) throw SessionWireFormatException("truncated_len")
        val len = buf.int
        if (len < 0) throw SessionWireFormatException("negative_len")
        if (buf.remaining() < len) throw SessionWireFormatException("truncated_payload")
        return len
    }

    private companion object {
        private val MAGIC = byteArrayOf('K'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
        private const val VERSION_V1: Byte = 1
        private const val VERSION_V2: Byte = 2
    }
}

class SessionWireFormatException(message: String) : IllegalArgumentException(message)
