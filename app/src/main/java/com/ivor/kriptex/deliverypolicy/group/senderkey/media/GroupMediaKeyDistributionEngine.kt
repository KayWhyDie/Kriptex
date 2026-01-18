package com.ivor.kriptex.deliverypolicy.group.senderkey.media

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.group.GroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.dataplane.SenderKeyGroupCrypto
import com.ivor.kriptex.deliverypolicy.protocol.GroupMediaKeyDistributionMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 4: Sender-key group media key distribution.
 *
 * - Encrypts the per-media 32-byte MediaKey using the group SenderKey chain.
 * - Receiver applies it idempotently, or buffers it if sender key is missing.
 */
class GroupMediaKeyDistributionEngine(
    private val localIdentityPublicKey: ByteArray,
    private val groupStore: GroupStore,
    private val senderKeyStore: SenderKeyStore,
    private val mediaKeyStore: GroupMediaKeyStore,
    private val pendingStore: GroupMediaPendingStore,
    private val unblock: GroupMediaUnblockCoordinator? = null,
    private val crypto: SenderKeyGroupCrypto = SenderKeyGroupCrypto(),
    private val clock: Clock = MonotonicClock,
) {

    init {
        require(localIdentityPublicKey.size == 32) { "local_identity_key_must_be_32_bytes" }
    }

    sealed interface InboundApplyResult {
        /** Message was accepted for storage/ACK; may or may not have produced a key yet. */
        data class Accepted(val applied: Boolean, val pending: Boolean = false) : InboundApplyResult

        data class Rejected(val reason: String) : InboundApplyResult
    }

    fun encryptOutbound(
        conversationId: String,
        messageId: String,
        createdAtElapsedMs: Long = clock.nowMs(),
        mediaId: String,
        mediaKey: ByteArray,
    ): GroupMediaKeyDistributionMessage {
        require(mediaId.isNotEmpty()) { "empty_media_id" }
        require(mediaKey.size == 32) { "media_key_must_be_32_bytes" }

        val group = groupStore.getByConversationId(conversationId) ?: throw IllegalStateException("unknown_group")
        if (!group.isMember(localIdentityPublicKey)) throw IllegalStateException("local_not_group_member")

        val state = senderKeyStore.get(group.groupId, localIdentityPublicKey) ?: throw IllegalStateException("missing_local_sender_key")

        val plaintext = PlaintextCodec.encode(mediaId = mediaId, mediaKey = mediaKey)
        val step = crypto.encrypt(
            state = state,
            groupId = group.groupId,
            messageId = messageId,
            conversationId = conversationId,
            plaintext = plaintext,
        )

        senderKeyStore.put(step.newState)

        return GroupMediaKeyDistributionMessage(
            messageId = messageId,
            conversationId = conversationId,
            createdAtElapsedMs = createdAtElapsedMs,
            groupId = group.groupId.copyBytes(),
            senderIdentityPublicKey = localIdentityPublicKey.copyOf(),
            senderKeyId = state.senderKeyId,
            counter = step.counter,
            mediaId = mediaId,
            ciphertext = step.ciphertext,
        )
    }

    fun applyInbound(authenticatedPeerIdentityPublicKey: ByteArray, msg: GroupMediaKeyDistributionMessage): InboundApplyResult {
        // Sender identity binding to the authenticated 1:1 session peer.
        if (!msg.senderIdentityPublicKey.contentEquals(authenticatedPeerIdentityPublicKey)) {
            return InboundApplyResult.Rejected("sender_identity_mismatch")
        }

        // Idempotent replay handling: if we've already stored a key for this mediaId, do not
        // attempt to decrypt again (which could advance/consume sender-key ratchet state).
        val existing = mediaKeyStore.get(msg.mediaId)
        if (existing != null) {
            val sameBinding =
                existing.groupId == GroupId(msg.groupId) &&
                    existing.senderIdentityPublicKey.contentEquals(msg.senderIdentityPublicKey) &&
                    existing.senderKeyId == msg.senderKeyId &&
                    existing.counter == msg.counter

            return if (sameBinding) {
                InboundApplyResult.Accepted(applied = false, pending = false)
            } else {
                InboundApplyResult.Rejected("media_id_rebind_conflict")
            }
        }

        val groupId = GroupId(msg.groupId)
        val group = groupStore.getById(groupId) ?: return InboundApplyResult.Rejected("unknown_group")

        // Membership enforcement.
        if (!group.isMember(authenticatedPeerIdentityPublicKey) || !group.isMember(localIdentityPublicKey)) {
            return InboundApplyResult.Rejected("non_member")
        }

        // Sender key must exist before we can decrypt; otherwise buffer for later.
        val state = senderKeyStore.get(groupId, authenticatedPeerIdentityPublicKey)
        if (state == null) {
            pendingStore.putIfAbsent(
                PendingGroupMediaKeyDistribution(
                    messageId = msg.messageId,
                    conversationId = msg.conversationId,
                    createdAtElapsedMs = msg.createdAtElapsedMs,
                    groupId = groupId,
                    senderIdentityPublicKey = msg.senderIdentityPublicKey,
                    senderKeyId = msg.senderKeyId,
                    counter = msg.counter,
                    mediaId = msg.mediaId,
                    ciphertext = msg.ciphertext,
                ),
            )
            return InboundApplyResult.Accepted(applied = false, pending = true)
        }

        // Rotation contract: wrong epoch fails deterministically.
        if (state.senderKeyId != msg.senderKeyId) {
            return InboundApplyResult.Rejected("sender_key_id_mismatch")
        }

        return when (val outcome = crypto.decrypt(
            state = state,
            groupId = groupId,
            senderIdentityPublicKey = msg.senderIdentityPublicKey,
            senderKeyId = msg.senderKeyId,
            counter = msg.counter,
            messageId = msg.messageId,
            conversationId = msg.conversationId,
            ciphertext = msg.ciphertext,
        )) {
            is SenderKeyGroupCrypto.DecryptOutcome.Rejected -> InboundApplyResult.Rejected(outcome.reason)

            is SenderKeyGroupCrypto.DecryptOutcome.Accepted -> {
                val decoded = try {
                    PlaintextCodec.decode(outcome.plaintext)
                } catch (_: Exception) {
                    return InboundApplyResult.Rejected("bad_plaintext")
                }

                if (decoded.mediaId != msg.mediaId) {
                    return InboundApplyResult.Rejected("media_id_mismatch")
                }

                val putRes = mediaKeyStore.putIfAbsent(
                    GroupMediaKeyEntry(
                        mediaId = msg.mediaId,
                        groupId = groupId,
                        senderIdentityPublicKey = msg.senderIdentityPublicKey,
                        senderKeyId = msg.senderKeyId,
                        counter = msg.counter,
                        mediaKey = decoded.mediaKey,
                    ),
                )

                if (putRes is GroupMediaKeyStore.PutResult.Conflict) {
                    return InboundApplyResult.Rejected(putRes.reason)
                }

                senderKeyStore.put(outcome.newState)
                pendingStore.remove(msg.messageId)
                unblock?.markMediaKeyAvailable(msg.mediaId)

                InboundApplyResult.Accepted(applied = true, pending = false)
            }
        }
    }

    /**
     * Called after a SenderKeyDistributionMessage for this sender has been applied.
     * Attempts to decrypt and apply any pending group media key distributions.
     */
    fun onSenderKeyAvailable(groupId: GroupId, senderIdentityPublicKey: ByteArray) {
        val pending = pendingStore.listFor(groupId, senderIdentityPublicKey)
        pending.forEach { p ->
            val msg = GroupMediaKeyDistributionMessage(
                messageId = p.messageId,
                conversationId = p.conversationId,
                createdAtElapsedMs = p.createdAtElapsedMs,
                groupId = p.groupId.copyBytes(),
                senderIdentityPublicKey = p.senderIdentityPublicKey,
                senderKeyId = p.senderKeyId,
                counter = p.counter,
                mediaId = p.mediaId,
                ciphertext = p.ciphertext,
            )

            val r = applyInbound(authenticatedPeerIdentityPublicKey = senderIdentityPublicKey, msg = msg)
            // If sender key is still missing (shouldn't happen here), keep pending.
            if (r is InboundApplyResult.Rejected) {
                pendingStore.remove(p.messageId)
            }
        }
    }

    private object PlaintextCodec {
        private const val VERSION: Int = 1
        private const val MAX_MEDIA_ID_BYTES: Int = 256

        data class Decoded(val mediaId: String, val mediaKey: ByteArray)

        fun encode(mediaId: String, mediaKey: ByteArray): ByteArray {
            val mid = mediaId.encodeToByteArray()
            require(mid.isNotEmpty()) { "empty_media_id" }
            require(mid.size <= MAX_MEDIA_ID_BYTES) { "media_id_too_long" }
            require(mediaKey.size == 32) { "media_key_must_be_32_bytes" }

            val buf = ByteBuffer.allocate(4 + 4 + mid.size + 4 + mediaKey.size).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(VERSION)
            putBytes(buf, mid)
            putBytes(buf, mediaKey)
            return buf.array()
        }

        fun decode(bytes: ByteArray): Decoded {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (buf.remaining() < 4) throw IllegalArgumentException("truncated")
            val v = buf.int
            if (v != VERSION) throw IllegalArgumentException("bad_version")

            val mid = readBytes(buf)
            if (mid.isEmpty() || mid.size > MAX_MEDIA_ID_BYTES) throw IllegalArgumentException("bad_media_id")
            val key = readBytes(buf)
            if (key.size != 32) throw IllegalArgumentException("bad_media_key")
            return Decoded(mediaId = mid.decodeToString(), mediaKey = key)
        }

        private fun putBytes(buf: ByteBuffer, bytes: ByteArray) {
            buf.putInt(bytes.size)
            buf.put(bytes)
        }

        private fun readBytes(buf: ByteBuffer): ByteArray {
            if (buf.remaining() < 4) throw IllegalArgumentException("truncated_len")
            val len = buf.int
            if (len < 0 || len > buf.remaining()) throw IllegalArgumentException("bad_len")
            val out = ByteArray(len)
            buf.get(out)
            return out
        }
    }
}
