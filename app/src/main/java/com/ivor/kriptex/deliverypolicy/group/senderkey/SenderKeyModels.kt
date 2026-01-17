package com.ivor.kriptex.deliverypolicy.group.senderkey

import com.ivor.kriptex.deliverypolicy.group.GroupId

/**
 * Per-sender, per-group sender key ratchet state.
 */
data class SenderKeyState(
    val groupId: GroupId,
    /** Sender Ed25519 identity public key (32 bytes). */
    val senderIdentityPublicKey: ByteArray,
    /** Logical sender key id for this (groupId, sender). Monotonically increases on rotation. */
    val senderKeyId: Long,
    /** Current chain key (32 bytes). */
    val chainKey: ByteArray,
    /** Next expected counter (starts at 1). */
    val nextCounter: Long,
    /**
     * Message keys for counters that were skipped (out-of-order support).
     *
     * Key: counter, Value: message key (32 bytes).
     */
    val skippedMessageKeys: Map<Long, ByteArray> = emptyMap(),
) {
    init {
        require(senderIdentityPublicKey.size == 32) { "sender_identity_key_must_be_32_bytes" }
        require(senderKeyId > 0) { "non_positive_sender_key_id" }
        require(chainKey.size == 32) { "chain_key_must_be_32_bytes" }
        require(nextCounter > 0) { "non_positive_counter" }

        skippedMessageKeys.forEach { (counter, mk) ->
            require(counter > 0) { "non_positive_skipped_counter" }
            require(mk.size == 32) { "skipped_message_key_must_be_32_bytes" }
        }
    }
}
