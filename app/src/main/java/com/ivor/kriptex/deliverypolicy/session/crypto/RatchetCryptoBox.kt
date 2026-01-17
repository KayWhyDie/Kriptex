package com.ivor.kriptex.deliverypolicy.session.crypto

import com.ivor.kriptex.deliverypolicy.protocol.ProtocolVersion
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.session.SessionState
import com.ivor.kriptex.deliverypolicy.session.ratchet.NoOpRatchetDebugTrace
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetDebugTrace
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetMachine
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetMessageHeader
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Double Ratchet-backed authenticated encryption at the session boundary.
 *
 * This preserves the existing envelope AEAD boundary and AAD binding, but replaces
 * the legacy static session key usage with per-message Double Ratchet message keys.
 */
class RatchetCryptoBox(
    private val session: SessionState,
    private val direction: Direction,
    private val blobCodec: CiphertextBlobCodec = CiphertextBlobCodec(),
    private val aeadDebug: SessionCryptoDebugTrace = NoOpSessionCryptoDebugTrace,
    private val ratchetDebug: RatchetDebugTrace = NoOpRatchetDebugTrace,
) {

    enum class Direction { OUTBOUND, INBOUND }

    data class EncryptResult(
        val updatedRatchet: RatchetState,
        val ciphertextBlobBytes: ByteArray,
        val envelopeSeq: Long,
        val header: RatchetMessageHeader,
    )

    data class DecryptResult(
        val updatedRatchet: RatchetState,
        val plaintext: ByteArray,
        val header: RatchetMessageHeader,
    )

    fun encrypt(
        ratchet: RatchetState,
        plaintextBytes: ByteArray,
        messageId: String,
        envelopeSeq: Long,
    ): EncryptResult {
        check(direction == Direction.OUTBOUND) { "encrypt called on INBOUND box" }
        check(session.isEstablished()) { "session_not_established" }
        check(session.aeadEnabled) { "aead_disabled" }

        val algorithm = session.aeadAlgorithm
        aeadDebug.onAlgorithmSelected(session.sessionId, algorithm)

        val step = RatchetMachine.nextOutbound(ratchet, debug = ratchetDebug, sessionId = session.sessionId)
        val headerBytes = step.header.encode()

        val nonce = deriveNonce(algorithm, session.sessionId, direction, envelopeSeq)
        val aad = aadBytes(session.sessionId, messageId, headerBytes)

        val ciphertext = try {
            aeadEncrypt(algorithm, step.messageKey, nonce, aad, plaintextBytes)
        } finally {
            // Best-effort wipe one-time message key.
            step.messageKey.fill(0)
        }

        aeadDebug.onEncrypt(session.sessionId, messageId, algorithm, plaintextBytes.size, ciphertext.size)

        val blobBytes = blobCodec.encode(
            CiphertextBlob(
                nonce = nonce,
                header = headerBytes,
                ciphertext = ciphertext,
            ),
        )

        return EncryptResult(updatedRatchet = step.updated, ciphertextBlobBytes = blobBytes, envelopeSeq = envelopeSeq, header = step.header)
    }

    fun decrypt(
        ratchet: RatchetState,
        ciphertextBlobBytes: ByteArray,
        messageId: String,
        envelopeSeq: Long,
    ): DecryptResult {
        check(direction == Direction.INBOUND) { "decrypt called on OUTBOUND box" }
        check(session.isEstablished()) { "session_not_established" }
        check(session.aeadEnabled) { "aead_disabled" }

        val algorithm = session.aeadAlgorithm
        val blob = try {
            blobCodec.decode(ciphertextBlobBytes)
        } catch (e: IllegalArgumentException) {
            aeadDebug.onDecryptRejected(session.sessionId, messageId, "bad_blob")
            throw e
        }

        if (blob.header.isEmpty()) {
            aeadDebug.onDecryptRejected(session.sessionId, messageId, "missing_ratchet_header")
            throw GeneralSecurityException("missing_ratchet_header")
        }

        val header = try {
            RatchetMessageHeader.decode(blob.header)
        } catch (e: IllegalArgumentException) {
            aeadDebug.onDecryptRejected(session.sessionId, messageId, "bad_ratchet_header")
            throw e
        }

        val expectedNonce = deriveNonce(algorithm, session.sessionId, direction, envelopeSeq)
        if (!blob.nonce.contentEquals(expectedNonce)) {
            aeadDebug.onDecryptRejected(session.sessionId, messageId, "nonce_mismatch")
            throw GeneralSecurityException("nonce_mismatch")
        }

        // Compute the ratchet step, but only commit it after AEAD verification.
        val step = try {
            RatchetMachine.nextInbound(ratchet, header = header, debug = ratchetDebug, sessionId = session.sessionId)
        } catch (e: Exception) {
            aeadDebug.onDecryptRejected(session.sessionId, messageId, "ratchet_rejected")
            throw e
        }

        val aad = aadBytes(session.sessionId, messageId, blob.header)

        try {
            val plaintext = aeadDecrypt(algorithm, step.messageKey, blob.nonce, aad, blob.ciphertext)
            aeadDebug.onDecrypt(session.sessionId, messageId, algorithm, blob.ciphertext.size, plaintext.size)
            return DecryptResult(updatedRatchet = step.updated, plaintext = plaintext, header = header)
        } catch (e: AEADBadTagException) {
            aeadDebug.onDecryptRejected(session.sessionId, messageId, "auth_failed")
            throw e
        } finally {
            step.messageKey.fill(0)
        }
    }

    private fun aadBytes(sessionId: String, messageId: String, ratchetHeaderBytes: ByteArray): ByteArray {
        val sid = sessionId.encodeToByteArray()
        val mid = messageId.encodeToByteArray()
        val hdr = ratchetHeaderBytes

        val buf = ByteBuffer
            .allocate(4 + (4 + sid.size) + (4 + mid.size) + (4 + hdr.size))
            .order(ByteOrder.BIG_ENDIAN)

        buf.putInt(ProtocolVersion.CURRENT)
        buf.putInt(sid.size)
        buf.put(sid)
        buf.putInt(mid.size)
        buf.put(mid)
        buf.putInt(hdr.size)
        buf.put(hdr)
        return buf.array()
    }

    private fun deriveNonce(algorithm: SessionAeadAlgorithm, sessionId: String, direction: Direction, seq: Long): ByteArray {
        require(seq > 0) { "non_positive_seq" }

        val nonceSize = when (algorithm) {
            SessionAeadAlgorithm.XCHACHA20_POLY1305 -> 24
            SessionAeadAlgorithm.AES_256_GCM -> 12
        }

        val prefixLen = nonceSize - 8

        val label = when (session.role) {
            com.ivor.kriptex.deliverypolicy.session.SessionRole.INITIATOR -> when (direction) {
                Direction.OUTBOUND -> "init->resp"
                Direction.INBOUND -> "resp->init"
            }

            com.ivor.kriptex.deliverypolicy.session.SessionRole.RESPONDER -> when (direction) {
                Direction.OUTBOUND -> "resp->init"
                Direction.INBOUND -> "init->resp"
            }
        }

        val md = MessageDigest.getInstance("SHA-256")
        md.update("KPX-DR-NONCE".encodeToByteArray())
        md.update(0)
        md.update(sessionId.encodeToByteArray())
        md.update(0)
        md.update(label.encodeToByteArray())
        md.update(0)
        md.update(algorithm.name.encodeToByteArray())
        val prefixFull = md.digest()

        val nonce = ByteArray(nonceSize)
        System.arraycopy(prefixFull, 0, nonce, 0, prefixLen)
        val seqBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(seq).array()
        System.arraycopy(seqBytes, 0, nonce, prefixLen, 8)
        return nonce
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
                val cipher = AeadRuntimeSupport.xchachaCipherInstance()
                val specClass = Class.forName("org.spongycastle.jcajce.spec.AEADParameterSpec")
                val spec = specClass
                    .getDeclaredConstructor(ByteArray::class.java, Int::class.javaPrimitiveType, ByteArray::class.java)
                    .newInstance(nonce, 128, aad)
                val keySpec = SecretKeySpec(key.copyOf(32), "ChaCha20")
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec as java.security.spec.AlgorithmParameterSpec)
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
                val cipher = AeadRuntimeSupport.xchachaCipherInstance()
                val specClass = Class.forName("org.spongycastle.jcajce.spec.AEADParameterSpec")
                val spec = specClass
                    .getDeclaredConstructor(ByteArray::class.java, Int::class.javaPrimitiveType, ByteArray::class.java)
                    .newInstance(nonce, 128, aad)
                val keySpec = SecretKeySpec(key.copyOf(32), "ChaCha20")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, spec as java.security.spec.AlgorithmParameterSpec)
                cipher.doFinal(ciphertext)
            }
        }
    }
}
