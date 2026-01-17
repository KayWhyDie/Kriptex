package com.ivor.kriptex.deliverypolicy.session.ratchet

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object RatchetKdf {

    private val INFO_RK = "KPX-DR-RK".encodeToByteArray()

    data class RootKeyStep(
        val newRootKey: ByteArray,
        val newChainKey: ByteArray,
    )

    data class ChainKeyStep(
        val newChainKey: ByteArray,
        val messageKey: ByteArray,
    )

    fun kdfRootKey(rootKey: ByteArray, dhOut: ByteArray): RootKeyStep {
        require(rootKey.isNotEmpty()) { "missing_root_key" }
        val out = HkdfSha256.derive(salt = rootKey, ikm = dhOut, info = INFO_RK, length = 64)
        val rk = out.copyOfRange(0, 32)
        val ck = out.copyOfRange(32, 64)
        // Best-effort wipe temporary.
        out.fill(0)
        return RootKeyStep(newRootKey = rk, newChainKey = ck)
    }

    fun kdfChainKey(chainKey: ByteArray): ChainKeyStep {
        // Signal-style: (CK', MK) = (HMAC(CK, 0x01), HMAC(CK, 0x02))
        val newCk = hmacSha256(chainKey, byteArrayOf(0x01))
        val mk = hmacSha256(chainKey, byteArrayOf(0x02))
        return ChainKeyStep(newChainKey = newCk, messageKey = mk)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
