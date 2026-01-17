package com.ivor.kriptex.deliverypolicy.session

import java.security.MessageDigest

/**
 * Placeholder crypto/KDF.
 *
 * NOT Double Ratchet; this is a deterministic shared-key derivation used
 * only to establish session metadata and ratchet-ready interfaces.
 */
object SessionCrypto {

    fun deriveSharedKey(
        sessionId: String,
        initiatorPublicKey: ByteArray,
        responderPublicKey: ByteArray,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")

        val (pubA, pubB) = ordered(initiatorPublicKey, responderPublicKey)
        val (nA, nB) = ordered(initiatorNonce, responderNonce)

        md.update(sessionId.encodeToByteArray())
        md.update(pubA)
        md.update(pubB)
        md.update(nA)
        md.update(nB)

        return md.digest()
    }

    private fun ordered(a: ByteArray, b: ByteArray): Pair<ByteArray, ByteArray> {
        val cmp = compareLex(a, b)
        return if (cmp <= 0) a to b else b to a
    }

    private fun compareLex(a: ByteArray, b: ByteArray): Int {
        val min = minOf(a.size, b.size)
        for (i in 0 until min) {
            val ai = a[i].toInt() and 0xFF
            val bi = b[i].toInt() and 0xFF
            if (ai != bi) return ai - bi
        }
        return a.size - b.size
    }
}
