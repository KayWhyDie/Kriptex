package com.ivor.kriptex.deliverypolicy.persistence

/**
 * Persistable snapshot of pending (blocked) group media key distributions.
 *
 * Used to support cut/reorder where a group media key distribution arrives before
 * the corresponding SenderKeyDistributionMessage has been applied.
 */
data class PersistedGroupMediaPendingSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val pending: List<PersistedPendingGroupMediaKeyDistribution>,
)

data class PersistedPendingGroupMediaKeyDistribution(
    val messageId: String,
    val conversationId: String,
    val createdAtElapsedMs: Long,
    val groupId: ByteArray,
    val senderIdentityPublicKey: ByteArray,
    val senderKeyId: Long,
    val counter: Long,
    val mediaId: String,
    val ciphertext: ByteArray,
)
