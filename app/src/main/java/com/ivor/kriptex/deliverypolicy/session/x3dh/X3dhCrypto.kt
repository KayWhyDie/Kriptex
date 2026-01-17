package com.ivor.kriptex.deliverypolicy.session.x3dh

import com.ivor.kriptex.deliverypolicy.session.ratchet.HkdfSha256
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetDh
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object X3dhCrypto {

    private val rng = SecureRandom()

    fun generateIdentityKeyPair(): X3dhIdentityKeyPair {
        val seed = ByteArray(32)
        rng.nextBytes(seed)
        val pub = Ed25519.publicKeyFromSeed(seed)
        return X3dhIdentityKeyPair(seed = seed, publicKey = pub)
    }

    fun generateX25519KeyPair(): RatchetDh.KeyPair = RatchetDh.DefaultKeyPairGenerator.generate()

    fun signSignedPreKey(identitySeed: ByteArray, signedPreKeyId: Int, signedPreKeyPublic: ByteArray): ByteArray {
        val msg = signedPreKeySignatureMessage(signedPreKeyId, signedPreKeyPublic)
        return Ed25519.sign(identitySeed, msg)
    }

    fun verifySignedPreKeySignature(identityPublic: ByteArray, signedPreKeyId: Int, signedPreKeyPublic: ByteArray, signature: ByteArray): Boolean {
        val msg = signedPreKeySignatureMessage(signedPreKeyId, signedPreKeyPublic)
        return Ed25519.verify(identityPublic, msg, signature)
    }

    private fun signedPreKeySignatureMessage(signedPreKeyId: Int, signedPreKeyPublic: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + 32).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(signedPreKeyId)
        buf.put(signedPreKeyPublic)
        return buf.array()
    }

    data class DerivedSecrets(
        /**
         * Initial root key material for Double Ratchet.
         *
         * This is an HKDF-Extract output (PRK), suitable for passing to [com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetState.initialize].
         */
        val initialRootKey: ByteArray,
        val confirmKey: ByteArray,
    )

    fun deriveSecrets(
        sharedSecret: ByteArray,
        initiatorIdentityPublicEd: ByteArray,
        responderIdentityPublicEd: ByteArray,
        sessionId: String,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
    ): DerivedSecrets {
        val salt = MessageDigest.getInstance("SHA-256").run {
            update("KPX-X3DH-SALT".encodeToByteArray())
            update(0)
            update(sessionId.encodeToByteArray())
            update(0)
            update(initiatorNonce)
            update(0)
            update(responderNonce)
            digest()
        }

        val ad = ByteBuffer.allocate(4 + 32 + 32).order(ByteOrder.BIG_ENDIAN).run {
            putInt(1) // version
            put(initiatorIdentityPublicEd)
            put(responderIdentityPublicEd)
            array()
        }

        val prk = HkdfSha256.extract(salt = salt, ikm = sharedSecret + ad)
        val confirmKey = HkdfSha256.expand(prk = prk, info = "KPX-X3DH-CONFIRM".encodeToByteArray(), length = 32)
        return DerivedSecrets(initialRootKey = prk, confirmKey = confirmKey)
    }

    fun confirmTag(confirmKey: ByteArray, transcript: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(confirmKey, "HmacSHA256"))
        return mac.doFinal(transcript)
    }

    fun computeSharedSecretInitiator(
        initiatorIdentitySeedEd: ByteArray,
        initiatorBasePrivateX: ByteArray,
        responderIdentityPublicEd: ByteArray,
        responderSignedPreKeyPublicX: ByteArray,
        responderOneTimePreKeyPublicX: ByteArray?,
    ): ByteArray {
        val ikA = Ed25519X25519.x25519PrivateFromEd25519Seed(initiatorIdentitySeedEd)
        val ikB_pub = Ed25519X25519.x25519PublicFromEd25519Public(responderIdentityPublicEd)

        val dh1 = RatchetDh.dh(ikA, responderSignedPreKeyPublicX)
        val dh2 = RatchetDh.dh(initiatorBasePrivateX, ikB_pub)
        val dh3 = RatchetDh.dh(initiatorBasePrivateX, responderSignedPreKeyPublicX)
        val dh4 = responderOneTimePreKeyPublicX?.let { RatchetDh.dh(initiatorBasePrivateX, it) }

        return if (dh4 != null) dh1 + dh2 + dh3 + dh4 else dh1 + dh2 + dh3
    }

    fun computeSharedSecretResponder(
        responderIdentitySeedEd: ByteArray,
        responderSignedPreKeyPrivateX: ByteArray,
        initiatorIdentityPublicEd: ByteArray,
        initiatorBasePublicX: ByteArray,
        responderOneTimePreKeyPrivateX: ByteArray?,
    ): ByteArray {
        val ikB = Ed25519X25519.x25519PrivateFromEd25519Seed(responderIdentitySeedEd)
        val ikA_pub = Ed25519X25519.x25519PublicFromEd25519Public(initiatorIdentityPublicEd)

        val dh1 = RatchetDh.dh(responderSignedPreKeyPrivateX, ikA_pub)
        val dh2 = RatchetDh.dh(ikB, initiatorBasePublicX)
        val dh3 = RatchetDh.dh(responderSignedPreKeyPrivateX, initiatorBasePublicX)
        val dh4 = responderOneTimePreKeyPrivateX?.let { RatchetDh.dh(it, initiatorBasePublicX) }

        return if (dh4 != null) dh1 + dh2 + dh3 + dh4 else dh1 + dh2 + dh3
    }

    internal object Ed25519 {
        fun publicKeyFromSeed(seed: ByteArray): ByteArray {
            val priv = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
            val pub = priv.generatePublicKey()
            val out = ByteArray(32)
            pub.encode(out, 0)
            return out
        }

        fun sign(seed: ByteArray, message: ByteArray): ByteArray {
            val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
            signer.init(true, org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0))
            signer.update(message, 0, message.size)
            return signer.generateSignature()
        }

        fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
            verifier.init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            return verifier.verifySignature(signature)
        }
    }

    internal object Ed25519X25519 {
        private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

        fun x25519PrivateFromEd25519Seed(seed: ByteArray): ByteArray {
            require(seed.size == 32) { "bad_identity_seed_len" }
            val h = MessageDigest.getInstance("SHA-512").digest(seed)
            val out = h.copyOfRange(0, 32)
            // Clamp per X25519.
            out[0] = (out[0].toInt() and 248).toByte()
            out[31] = (out[31].toInt() and 127).toByte()
            out[31] = (out[31].toInt() or 64).toByte()
            return out
        }

        fun x25519PublicFromEd25519Public(edPublic: ByteArray): ByteArray {
            require(edPublic.size == 32) { "bad_identity_public_len" }
            val yBytes = edPublic.copyOf()
            yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte() // clear sign bit
            val y = littleEndianToBigInteger(yBytes)

            // u = (1+y)/(1-y) mod p
            val one = BigInteger.ONE
            val num = one.add(y).mod(P)
            val den = one.subtract(y).mod(P)
            val inv = den.modInverse(P)
            val u = num.multiply(inv).mod(P)
            return bigIntegerToLittleEndian32(u)
        }

        private fun littleEndianToBigInteger(le: ByteArray): BigInteger {
            val be = le.copyOf().apply { reverse() }
            return BigInteger(1, be)
        }

        private fun bigIntegerToLittleEndian32(v: BigInteger): ByteArray {
            var be = v.mod(P).toByteArray()
            if (be.size > 32) {
                // BigInteger may produce a leading 0 byte for sign.
                be = be.copyOfRange(be.size - 32, be.size)
            }
            val out = ByteArray(32)
            val start = out.size - be.size
            System.arraycopy(be, 0, out, start, be.size)
            out.reverse()
            return out
        }
    }
}
