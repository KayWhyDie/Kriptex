package com.ivor.kriptex.deliverypolicy.session.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Self-describing binary codec for [CiphertextBlob].
 */
class CiphertextBlobCodec {

    fun encode(blob: CiphertextBlob): ByteArray {
        // Backward-compatible: emit v1 when there is no header.
        return if (blob.header.isEmpty()) {
            val total = 4 + 1 + (4 + blob.nonce.size) + (4 + blob.ciphertext.size)
            val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            buf.put(MAGIC)
            buf.put(VERSION_V1)
            putBytes(buf, blob.nonce)
            putBytes(buf, blob.ciphertext)
            buf.array()
        } else {
            val total = 4 + 1 + (4 + blob.nonce.size) + (4 + blob.header.size) + (4 + blob.ciphertext.size)
            val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            buf.put(MAGIC)
            buf.put(VERSION)
            putBytes(buf, blob.nonce)
            putBytes(buf, blob.header)
            putBytes(buf, blob.ciphertext)
            buf.array()
        }
    }

    fun decode(bytes: ByteArray): CiphertextBlob {
        if (bytes.size < 5) throw CiphertextWireFormatException("too_short")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4)
        buf.get(magic)
        if (!magic.contentEquals(MAGIC)) throw CiphertextWireFormatException("bad_magic")
        val ver = buf.get()
        if (ver != VERSION && ver != VERSION_V1) throw CiphertextWireFormatException("unsupported_version=$ver")

        val nonce = readBytes(buf)
        return if (ver == VERSION_V1) {
            val ciphertext = readBytes(buf)
            CiphertextBlob(nonce = nonce, header = byteArrayOf(), ciphertext = ciphertext)
        } else {
            val header = readBytes(buf)
            val ciphertext = readBytes(buf)
            CiphertextBlob(nonce = nonce, header = header, ciphertext = ciphertext)
        }
    }

    private fun putBytes(buf: ByteBuffer, bytes: ByteArray) {
        buf.putInt(bytes.size)
        buf.put(bytes)
    }

    private fun readBytes(buf: ByteBuffer): ByteArray {
        if (buf.remaining() < 4) throw CiphertextWireFormatException("truncated_len")
        val len = buf.int
        if (len < 0) throw CiphertextWireFormatException("negative_len")
        if (buf.remaining() < len) throw CiphertextWireFormatException("truncated_payload")
        if (len == 0) return byteArrayOf()
        val b = ByteArray(len)
        buf.get(b)
        return b
    }

    private companion object {
        private val MAGIC = byteArrayOf('K'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
        private const val VERSION_V1: Byte = 1
        private const val VERSION: Byte = 2
    }
}

class CiphertextWireFormatException(message: String) : IllegalArgumentException(message)
