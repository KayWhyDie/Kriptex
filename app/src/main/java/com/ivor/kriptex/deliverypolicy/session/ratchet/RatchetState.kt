package com.ivor.kriptex.deliverypolicy.session.ratchet

/**
 * Double Ratchet state (Signal semantics).
 *
 * - RK: root key
 * - CKs/CKr: sending/receiving chain keys
 * - DHs: local DH keypair
 * - DHr: remote DH public key
 * - Ns/Nr/PN: message counters
 * - skipped: bounded storage for skipped message keys
 */
data class RatchetState(
    val rootKey: ByteArray,
    val sendingChainKey: ByteArray,
    val receivingChainKey: ByteArray,
    val localDhPrivateKey: ByteArray,
    val localDhPublicKey: ByteArray,
    val remoteDhPublicKey: ByteArray,
    val ns: Int,
    val nr: Int,
    val pn: Int,
    val pendingSendDhRatchet: Boolean,
    val skippedKeys: List<SkippedMessageKey>,
) {

    data class SkippedMessageKey(
        val dhPublicKey: ByteArray,
        val n: Int,
        val messageKey: ByteArray,
    ) {
        init {
            require(dhPublicKey.size == 32) { "bad_dh_pub_len" }
            require(n >= 0) { "negative_n" }
        }
    }

    fun isInitialized(): Boolean {
        return rootKey.isNotEmpty() && localDhPrivateKey.size == 32 && localDhPublicKey.size == 32 && remoteDhPublicKey.size == 32
    }

    companion object {
        const val MAX_SKIPPED_KEYS: Int = 2000
        const val MAX_SKIP: Int = 2000

        internal fun initialize(
            role: String,
            initialRootKey: ByteArray,
            localDh: RatchetDh.KeyPair,
            remoteDhPublicKey: ByteArray,
        ): RatchetState {
            // Derive initial directional chain keys from RK using role-separated labels.
            val ck1 = HkdfSha256.expand(prk = initialRootKey, info = "KPX-DR-CK1".encodeToByteArray(), length = 32)
            val ck2 = HkdfSha256.expand(prk = initialRootKey, info = "KPX-DR-CK2".encodeToByteArray(), length = 32)

            val sendCk: ByteArray
            val recvCk: ByteArray
            if (role == "INITIATOR") {
                sendCk = ck1
                recvCk = ck2
            } else {
                sendCk = ck2
                recvCk = ck1
            }

            return RatchetState(
                rootKey = initialRootKey,
                sendingChainKey = sendCk,
                receivingChainKey = recvCk,
                localDhPrivateKey = localDh.privateKey,
                localDhPublicKey = localDh.publicKey,
                remoteDhPublicKey = remoteDhPublicKey,
                ns = 0,
                nr = 0,
                pn = 0,
                pendingSendDhRatchet = false,
                skippedKeys = emptyList(),
            )
        }
    }
}
