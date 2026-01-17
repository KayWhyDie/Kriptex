package com.ivor.kriptex.deliverypolicy.session.ratchet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Double Ratchet message header.
 *
 * Matches Signal semantics: header is sent in cleartext but must be authenticated (included in AEAD AAD).
 */
data class RatchetMessageHeader(
    /** Sender's current DH public key (X25519, 32 bytes). */
    val dhPublicKey: ByteArray,
    /** Message number in current sending chain. */
    val n: Int,
    /** Length of previous sending chain (PN). */
    val pn: Int,
) {
    init {
        require(dhPublicKey.size == 32) { "bad_dh_pub_len" }
        require(n >= 0) { "negative_n" }
        require(pn >= 0) { "negative_pn" }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(4 + 1 + 4 + 4 + 32).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(VERSION)
        buf.putInt(n)
        buf.putInt(pn)
        buf.put(dhPublicKey)
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): RatchetMessageHeader {
            if (bytes.size < 4 + 1 + 4 + 4 + 32) throw IllegalArgumentException("header_too_short")
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            if (!magic.contentEquals(MAGIC)) throw IllegalArgumentException("bad_header_magic")
            val ver = buf.get()
            if (ver != VERSION) throw IllegalArgumentException("bad_header_version=$ver")
            val n = buf.int
            val pn = buf.int
            val dh = ByteArray(32)
            buf.get(dh)
            return RatchetMessageHeader(dhPublicKey = dh, n = n, pn = pn)
        }

        private val MAGIC = byteArrayOf('K'.code.toByte(), 'R'.code.toByte(), 'H'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1
    }
}
