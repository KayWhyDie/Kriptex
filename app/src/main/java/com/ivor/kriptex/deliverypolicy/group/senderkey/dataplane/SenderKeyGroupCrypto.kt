package com.ivor.kriptex.deliverypolicy.group.senderkey.dataplane

import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyState
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolVersion
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sender-key crypto for group messages.
 *
 * - Signal-style chain key ratchet: (CK', MK) = (HMAC(CK, 0x01), HMAC(CK, 0x02))
 * - AEAD: AES-256-GCM (always available)
 * - Replay/out-of-order: stores skipped message keys up to [maxSkip]
 *
 * Important: state commits ONLY after successful AEAD authentication.
 */
class SenderKeyGroupCrypto(
    private val algorithm: SessionAeadAlgorithm = SessionAeadAlgorithm.AES_256_GCM,
    private val maxSkip: Int = 64,
    private val debugTrace: SenderKeyGroupMessageDebugTrace = NoOpSenderKeyGroupMessageDebugTrace,
) {

    sealed interface DecryptOutcome {
        data class Accepted(val plaintext: ByteArray, val newState: SenderKeyState) : DecryptOutcome
        data class Rejected(val reason: String) : DecryptOutcome
    }

    data class EncryptOutcome(
        val counter: Long,
        val ciphertext: ByteArray,
        val newState: SenderKeyState,
    )

    fun encrypt(
        state: SenderKeyState,
        groupId: GroupId,
        messageId: String,
        conversationId: String,
        plaintext: ByteArray,
    ): EncryptOutcome {
        require(plaintext.isNotEmpty()) { "empty_plaintext" }

        val counter = state.nextCounter
        val step = kdfChainKey(state.chainKey)

        val aad = aadBytes(
            groupId = groupId,
            senderIdentityPublicKey = state.senderIdentityPublicKey,
            senderKeyId = state.senderKeyId,
            counter = counter,
            messageId = messageId,
            conversationId = conversationId,
        )
        val nonce = deriveNonce(step.messageKey, groupId, state.senderKeyId, counter)

        val ciphertext = try {
            aeadEncrypt(
                algorithm = algorithm,
                key = step.messageKey,
                nonce = nonce,
                aad = aad,
                plaintext = plaintext,
            )
        } catch (e: Exception) {
            throw GeneralSecurityException("encrypt_failed", e)
        }

        val newState = state.copy(
            chainKey = step.newChainKey,
            nextCounter = counter + 1,
        )
        debugTrace.onEncrypt(groupId, state.senderKeyId, counter, plaintext.size, ciphertext.size)
        return EncryptOutcome(counter = counter, ciphertext = ciphertext, newState = newState)
    }

    fun decrypt(
        state: SenderKeyState,
        groupId: GroupId,
        senderIdentityPublicKey: ByteArray,
        senderKeyId: Long,
        counter: Long,
        messageId: String,
        conversationId: String,
        ciphertext: ByteArray,
    ): DecryptOutcome {
        if (senderKeyId != state.senderKeyId) {
            debugTrace.onDecryptRejected(groupId, senderKeyId, counter, "sender_key_id_mismatch")
            return DecryptOutcome.Rejected("sender_key_id_mismatch")
        }
        if (counter <= 0) {
            debugTrace.onDecryptRejected(groupId, senderKeyId, counter, "non_positive_counter")
            return DecryptOutcome.Rejected("non_positive_counter")
        }

        // Fast-path: already-derived (out-of-order).
        val existingMk = state.skippedMessageKeys[counter]
        if (existingMk != null) {
            val aad = aadBytes(groupId, senderIdentityPublicKey, senderKeyId, counter, messageId, conversationId)
            val nonce = deriveNonce(existingMk, groupId, senderKeyId, counter)
            val plaintext = try {
                aeadDecrypt(algorithm, existingMk, nonce, aad, ciphertext)
            } catch (e: AEADBadTagException) {
                debugTrace.onDecryptRejected(groupId, senderKeyId, counter, "auth_failed")
                return DecryptOutcome.Rejected("auth_failed")
            } catch (e: Exception) {
                debugTrace.onDecryptRejected(groupId, senderKeyId, counter, "decrypt_failed")
                return DecryptOutcome.Rejected("decrypt_failed")
            }

            val newSkipped = LinkedHashMap(state.skippedMessageKeys)
            newSkipped.remove(counter)
            val newState = state.copy(skippedMessageKeys = newSkipped)
            debugTrace.onDecrypt(groupId, senderKeyId, counter, ciphertext.size, plaintext.size)
            return DecryptOutcome.Accepted(plaintext = plaintext, newState = newState)
        }

        // Replay (counter behind window).
        if (counter < state.nextCounter) {
            debugTrace.onDecryptRejected(groupId, senderKeyId, counter, "replay")
            return DecryptOutcome.Rejected("replay")
        }

        val gap = counter - state.nextCounter
        if (gap > maxSkip.toLong()) {
            debugTrace.onDecryptRejected(groupId, senderKeyId, counter, "counter_too_far")
            return DecryptOutcome.Rejected("counter_too_far")
        }

        // Derive forward up to counter (do not mutate state until authenticated).
        var ck = state.chainKey.copyOf()
        val derivedSkipped = LinkedHashMap<Long, ByteArray>()
        var mkForCounter: ByteArray? = null

        var i = state.nextCounter
        while (i <= counter) {
            val step = kdfChainKey(ck)
            ck = step.newChainKey
            if (i == counter) {
                mkForCounter = step.messageKey
            } else {
                derivedSkipped[i] = step.messageKey
            }
            i++
        }

        val mk = mkForCounter ?: run {
            debugTrace.onDecryptRejected(groupId, senderKeyId, counter, "missing_message_key")
            return DecryptOutcome.Rejected("missing_message_key")
        }

        val aad = aadBytes(groupId, senderIdentityPublicKey, senderKeyId, counter, messageId, conversationId)
        val nonce = deriveNonce(mk, groupId, senderKeyId, counter)

        val plaintext = try {
            aeadDecrypt(algorithm, mk, nonce, aad, ciphertext)
        } catch (e: AEADBadTagException) {
            debugTrace.onDecryptRejected(groupId, senderKeyId, counter, "auth_failed")
            return DecryptOutcome.Rejected("auth_failed")
        } catch (e: Exception) {
            debugTrace.onDecryptRejected(groupId, senderKeyId, counter, "decrypt_failed")
            return DecryptOutcome.Rejected("decrypt_failed")
        }

        // Commit: advance chain key + nextCounter, and store derived skipped keys (bounded).
        val newSkipped = LinkedHashMap<Long, ByteArray>()
        newSkipped.putAll(state.skippedMessageKeys)
        derivedSkipped.forEach { (k, v) -> newSkipped[k] = v }
        pruneSkippedInPlace(newSkipped)

        val newState = state.copy(
            chainKey = ck,
            nextCounter = counter + 1,
            skippedMessageKeys = newSkipped,
        )

        debugTrace.onDecrypt(groupId, senderKeyId, counter, ciphertext.size, plaintext.size)
        return DecryptOutcome.Accepted(plaintext = plaintext, newState = newState)
    }

    private fun pruneSkippedInPlace(skipped: LinkedHashMap<Long, ByteArray>) {
        if (skipped.size <= maxSkip) return
        val keys = skipped.keys.sorted()
        val toRemove = skipped.size - maxSkip
        for (j in 0 until toRemove) {
            skipped.remove(keys[j])
        }
    }

    private data class ChainKeyStep(val newChainKey: ByteArray, val messageKey: ByteArray)

    private fun kdfChainKey(chainKey: ByteArray): ChainKeyStep {
        val newCk = hmacSha256(chainKey, byteArrayOf(0x01))
        val mk = hmacSha256(chainKey, byteArrayOf(0x02))
        return ChainKeyStep(newChainKey = newCk, messageKey = mk)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun aadBytes(
        groupId: GroupId,
        senderIdentityPublicKey: ByteArray,
        senderKeyId: Long,
        counter: Long,
        messageId: String,
        conversationId: String,
    ): ByteArray {
        val gid = groupId.copyBytes()
        val sid = senderIdentityPublicKey
        val mid = messageId.encodeToByteArray()
        val cid = conversationId.encodeToByteArray()

        val buf = ByteBuffer.allocate(
            4 +
                (4 + gid.size) +
                (4 + sid.size) +
                8 +
                8 +
                (4 + mid.size) +
                (4 + cid.size),
        ).order(ByteOrder.BIG_ENDIAN)

        buf.putInt(ProtocolVersion.CURRENT)
        putBytes(buf, gid)
        putBytes(buf, sid)
        buf.putLong(senderKeyId)
        buf.putLong(counter)
        putBytes(buf, mid)
        putBytes(buf, cid)
        return buf.array()
    }

    private fun deriveNonce(messageKey: ByteArray, groupId: GroupId, senderKeyId: Long, counter: Long): ByteArray {
        val nonceSize = when (algorithm) {
            SessionAeadAlgorithm.AES_256_GCM -> 12
            SessionAeadAlgorithm.XCHACHA20_POLY1305 -> 24
        }

        val md = MessageDigest.getInstance("SHA-256")
        md.update(messageKey)
        md.update(0)
        md.update("KPX-SKG-NONCE".encodeToByteArray())
        md.update(0)
        md.update(groupId.copyBytes())
        md.update(0)
        md.update(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(senderKeyId).array())
        md.update(0)
        md.update(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(counter).array())
        return md.digest().copyOf(nonceSize)
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
                throw GeneralSecurityException("xchacha_not_supported_for_sender_key")
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
                throw GeneralSecurityException("xchacha_not_supported_for_sender_key")
            }
        }
    }

    private fun putBytes(buf: ByteBuffer, bytes: ByteArray) {
        buf.putInt(bytes.size)
        buf.put(bytes)
    }
}
