package com.ivor.kriptex.deliverypolicy.session.crypto

import com.ivor.kriptex.deliverypolicy.protocol.ProtocolVersion
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.session.SessionRole
import com.ivor.kriptex.deliverypolicy.session.SessionState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.Security
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Session-scoped authenticated encryption.
 *
 * This is PRE-Double-Ratchet but is ratchet-ready:
 * - uses per-direction derived keys
 * - binds ciphertext to (sessionId, messageId, protocolVersion) via AAD
 */
class SessionCryptoBox private constructor(
    private val state: SessionState,
    private val direction: Direction,
    private val allocateOutboundSeq: (() -> Long)?,
    private val blobCodec: CiphertextBlobCodec = CiphertextBlobCodec(),
    private val debugTrace: SessionCryptoDebugTrace = NoOpSessionCryptoDebugTrace,
) {

    enum class Direction { OUTBOUND, INBOUND }

    fun encrypt(plaintextBytes: ByteArray, messageId: String): ByteArray {
        check(direction == Direction.OUTBOUND) { "encrypt called on INBOUND box" }
        val seq = allocateOutboundSeq?.invoke() ?: throw IllegalStateException("missing_seq_allocator")

        val algorithm = establishedAlgorithm()
        debugTrace.onAlgorithmSelected(state.sessionId, algorithm)

        val key = deriveDirectionKey(algorithm)
        val nonce = deriveNonce(algorithm, seq)
        val aad = aadBytes(state.sessionId, messageId)

        val ciphertext = aeadEncrypt(algorithm, key, nonce, aad, plaintextBytes)
        debugTrace.onEncrypt(state.sessionId, messageId, algorithm, plaintextBytes.size, ciphertext.size)

        return blobCodec.encode(CiphertextBlob(nonce = nonce, header = byteArrayOf(), ciphertext = ciphertext))
    }

    fun decrypt(ciphertextBlobBytes: ByteArray, messageId: String, envelopeSeq: Long): ByteArray {
        check(direction == Direction.INBOUND) { "decrypt called on OUTBOUND box" }

        val algorithm = establishedAlgorithm()
        val blob = try {
            blobCodec.decode(ciphertextBlobBytes)
        } catch (e: IllegalArgumentException) {
            debugTrace.onDecryptRejected(state.sessionId, messageId, "bad_blob")
            throw e
        }

        val expectedNonce = deriveNonce(algorithm, envelopeSeq)
        if (!blob.nonce.contentEquals(expectedNonce)) {
            debugTrace.onDecryptRejected(state.sessionId, messageId, "nonce_mismatch")
            throw GeneralSecurityException("nonce_mismatch")
        }

        val key = deriveDirectionKey(algorithm)
        val aad = aadBytes(state.sessionId, messageId)

        try {
            val plaintext = aeadDecrypt(algorithm, key, blob.nonce, aad, blob.ciphertext)
            debugTrace.onDecrypt(state.sessionId, messageId, algorithm, blob.ciphertext.size, plaintext.size)
            return plaintext
        } catch (e: AEADBadTagException) {
            debugTrace.onDecryptRejected(state.sessionId, messageId, "auth_failed")
            throw e
        }
    }

    private fun establishedAlgorithm(): SessionAeadAlgorithm {
        check(state.isEstablished()) { "session_not_established" }
        check(state.aeadEnabled) { "aead_disabled" }
        return state.aeadAlgorithm
    }

    private fun deriveDirectionKey(algorithm: SessionAeadAlgorithm): ByteArray {
        val sharedKey = state.sharedKey ?: throw IllegalStateException("missing_shared_key")
        val label = when (state.role) {
            SessionRole.INITIATOR -> when (direction) {
                Direction.OUTBOUND -> "init->resp"
                Direction.INBOUND -> "resp->init"
            }

            SessionRole.RESPONDER -> when (direction) {
                Direction.OUTBOUND -> "resp->init"
                Direction.INBOUND -> "init->resp"
            }
        }

        val md = MessageDigest.getInstance("SHA-256")
        md.update(sharedKey)
        md.update(0)
        md.update(label.encodeToByteArray())
        md.update(0)
        md.update(algorithm.name.encodeToByteArray())
        md.update(0)
        md.update("key".encodeToByteArray())
        return md.digest()
    }

    private fun deriveNonce(algorithm: SessionAeadAlgorithm, seq: Long): ByteArray {
        require(seq > 0) { "non_positive_seq" }
        val nonceSize = when (algorithm) {
            SessionAeadAlgorithm.XCHACHA20_POLY1305 -> 24
            SessionAeadAlgorithm.AES_256_GCM -> 12
        }
        val prefixLen = nonceSize - 8

        val sharedKey = state.sharedKey ?: throw IllegalStateException("missing_shared_key")
        val label = when (state.role) {
            SessionRole.INITIATOR -> when (direction) {
                Direction.OUTBOUND -> "init->resp"
                Direction.INBOUND -> "resp->init"
            }

            SessionRole.RESPONDER -> when (direction) {
                Direction.OUTBOUND -> "resp->init"
                Direction.INBOUND -> "init->resp"
            }
        }

        val md = MessageDigest.getInstance("SHA-256")
        md.update(sharedKey)
        md.update(0)
        md.update(label.encodeToByteArray())
        md.update(0)
        md.update(algorithm.name.encodeToByteArray())
        md.update(0)
        md.update("nonce_prefix".encodeToByteArray())
        val prefixFull = md.digest()

        val nonce = ByteArray(nonceSize)
        System.arraycopy(prefixFull, 0, nonce, 0, prefixLen)
        val seqBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(seq).array()
        System.arraycopy(seqBytes, 0, nonce, prefixLen, 8)
        return nonce
    }

    private fun aadBytes(sessionId: String, messageId: String): ByteArray {
        val sid = sessionId.encodeToByteArray()
        val mid = messageId.encodeToByteArray()
        val buf = ByteBuffer.allocate(4 + 4 + sid.size + 4 + mid.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(ProtocolVersion.CURRENT)
        buf.putInt(sid.size)
        buf.put(sid)
        buf.putInt(mid.size)
        buf.put(mid)
        return buf.array()
    }

    private fun aeadEncrypt(
        algorithm: SessionAeadAlgorithm,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        return when (algorithm) {
            SessionAeadAlgorithm.AES_256_GCM -> {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(aad)
                cipher.doFinal(plaintext)
            }

            SessionAeadAlgorithm.XCHACHA20_POLY1305 -> {
                // Best-effort: requires a provider that supports XChaCha20-Poly1305.
                ensureSpongyCastleProvider()
                val (cipher, keyAlg, params) = xchachaCipher(ENCRYPT = true, key = key, nonce = nonce, aad = aad)
                cipher.doFinal(plaintext)
            }
        }
    }

    private fun aeadDecrypt(
        algorithm: SessionAeadAlgorithm,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        return when (algorithm) {
            SessionAeadAlgorithm.AES_256_GCM -> {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(aad)
                cipher.doFinal(ciphertext)
            }

            SessionAeadAlgorithm.XCHACHA20_POLY1305 -> {
                ensureSpongyCastleProvider()
                val (cipher, _, _) = xchachaCipher(ENCRYPT = false, key = key, nonce = nonce, aad = aad)
                cipher.doFinal(ciphertext)
            }
        }
    }

    private fun ensureSpongyCastleProvider() {
        // The project already uses SpongyCastle in other areas.
        // Install once if not present.
        if (Security.getProvider("SC") != null) return
        try {
            val clazz = Class.forName("org.spongycastle.jce.provider.BouncyCastleProvider")
            val provider = clazz.getDeclaredConstructor().newInstance() as java.security.Provider
            Security.addProvider(provider)
        } catch (_: Throwable) {
            // Ignore: provider may already be present or unavailable.
        }
    }

    private fun xchachaCipher(ENCRYPT: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray): Triple<Cipher, String, Any> {
        val algoCandidates = listOf(
            "XChaCha20-Poly1305",
            "XCHACHA20-POLY1305",
        )

        val last = ArrayList<Throwable>(algoCandidates.size)
        for (name in algoCandidates) {
            try {
                val cipher = try {
                    Cipher.getInstance(name, "SC")
                } catch (_: Throwable) {
                    Cipher.getInstance(name)
                }

                // AEADParameterSpec is provided by SpongyCastle.
                val specClass = Class.forName("org.spongycastle.jcajce.spec.AEADParameterSpec")
                val spec = specClass
                    .getDeclaredConstructor(ByteArray::class.java, Int::class.javaPrimitiveType, ByteArray::class.java)
                    .newInstance(nonce, 128, aad)

                val keySpec = SecretKeySpec(key.copyOf(32), "ChaCha20")
                cipher.init(if (ENCRYPT) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec, spec as java.security.spec.AlgorithmParameterSpec)
                return Triple(cipher, "ChaCha20", spec)
            } catch (t: Throwable) {
                last.add(t)
            }
        }

        throw GeneralSecurityException("xchacha_unavailable")
    }

    companion object {
        /**
         * Best-effort availability check for negotiated algorithms.
         *
         * AES/GCM is always assumed available on Android/JVM.
         * XChaCha20-Poly1305 depends on an installed crypto provider.
         */
        fun isAlgorithmAvailable(algorithm: SessionAeadAlgorithm): Boolean {
            return when (algorithm) {
                SessionAeadAlgorithm.AES_256_GCM -> true
                SessionAeadAlgorithm.XCHACHA20_POLY1305 -> {
                    try {
                        // Attempt to make provider available (no-op if absent).
                        if (Security.getProvider("SC") == null) {
                            try {
                                val clazz = Class.forName("org.spongycastle.jce.provider.BouncyCastleProvider")
                                val provider = clazz.getDeclaredConstructor().newInstance() as java.security.Provider
                                Security.addProvider(provider)
                            } catch (_: Throwable) {
                                // ignore
                            }
                        }

                        // Cipher algorithm + AEADParameterSpec presence are required.
                        Class.forName("org.spongycastle.jcajce.spec.AEADParameterSpec")
                        val name = listOf("XChaCha20-Poly1305", "XCHACHA20-POLY1305").firstOrNull { n ->
                            try {
                                Cipher.getInstance(n)
                                true
                            } catch (_: Throwable) {
                                try {
                                    Cipher.getInstance(n, "SC")
                                    true
                                } catch (_: Throwable) {
                                    false
                                }
                            }
                        }
                        name != null
                    } catch (_: Throwable) {
                        false
                    }
                }
            }
        }

        fun outbound(
            state: SessionState,
            allocateOutboundSeq: () -> Long,
            debugTrace: SessionCryptoDebugTrace = NoOpSessionCryptoDebugTrace,
        ): SessionCryptoBox {
            return SessionCryptoBox(
                state = state,
                direction = Direction.OUTBOUND,
                allocateOutboundSeq = allocateOutboundSeq,
                debugTrace = debugTrace,
            )
        }

        fun inbound(
            state: SessionState,
            debugTrace: SessionCryptoDebugTrace = NoOpSessionCryptoDebugTrace,
        ): SessionCryptoBox {
            return SessionCryptoBox(
                state = state,
                direction = Direction.INBOUND,
                allocateOutboundSeq = null,
                debugTrace = debugTrace,
            )
        }
    }
}
