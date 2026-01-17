package com.ivor.kriptex.deliverypolicy.session.ratchet

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object HkdfSha256 {

    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val key = salt ?: ByteArray(32) { 0 }
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length >= 0) { "negative_length" }
        require(length <= 32 * 255) { "length_too_large" }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1

        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()

            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, toCopy)
            offset += toCopy
            counter += 1
        }

        return out
    }

    fun derive(salt: ByteArray?, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        return expand(prk, info, length)
    }
}
