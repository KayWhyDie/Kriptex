package com.ivor.kriptex.deliverypolicy.session.ratchet

import java.security.SecureRandom

internal object RatchetDh {

    private const val DH_PUB_LEN = 32
    private const val DH_PRIV_LEN = 32

    data class KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    ) {
        init {
            require(privateKey.size == DH_PRIV_LEN) { "bad_private_key_len" }
            require(publicKey.size == DH_PUB_LEN) { "bad_public_key_len" }
        }
    }

    interface KeyPairGenerator {
        fun generate(): KeyPair
    }

    object DefaultKeyPairGenerator : KeyPairGenerator {
        private val rng = SecureRandom()

        override fun generate(): KeyPair {
            // Use BouncyCastle X25519 primitives.
            val priv = ByteArray(DH_PRIV_LEN)
            rng.nextBytes(priv)

            val privParams = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(priv, 0)
            val pubParams = privParams.generatePublicKey()

            val pub = ByteArray(DH_PUB_LEN)
            pubParams.encode(pub, 0)

            return KeyPair(privateKey = priv, publicKey = pub)
        }
    }

    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == DH_PRIV_LEN) { "bad_private_key_len" }
        val privParams = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(privateKey, 0)
        val pubParams = privParams.generatePublicKey()
        val pub = ByteArray(DH_PUB_LEN)
        pubParams.encode(pub, 0)
        return pub
    }

    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == DH_PRIV_LEN) { "bad_private_key_len" }
        require(publicKey.size == DH_PUB_LEN) { "bad_public_key_len" }

        val privParams = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(privateKey, 0)
        val pubParams = org.bouncycastle.crypto.params.X25519PublicKeyParameters(publicKey, 0)

        val agreement = org.bouncycastle.crypto.agreement.X25519Agreement()
        agreement.init(privParams)
        val out = ByteArray(32)
        agreement.calculateAgreement(pubParams, out, 0)
        return out
    }
}
