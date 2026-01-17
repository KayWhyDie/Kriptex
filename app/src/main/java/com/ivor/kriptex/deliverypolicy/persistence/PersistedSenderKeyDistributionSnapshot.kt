package com.ivor.kriptex.deliverypolicy.persistence

/**
 * Persistable distribution state for Sender Key Distribution Protocol.
 *
 * Notes:
 * - No implicit resend on restore.
 * - Contains no sender chain keys; those live in SenderKeyStore snapshots.
 */
data class PersistedSenderKeyDistributionSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val states: List<PersistedSenderKeyDistributionState>,
    val pending: List<PersistedSenderKeyDistributionPending>,
)

data class PersistedSenderKeyDistributionState(
    val groupId: ByteArray,
    val senderIdentityPublicKey: ByteArray,
    val currentSenderKeyId: Long,
    val deliveredRecipientIdentityPublicKeys: List<ByteArray>,
)

data class PersistedSenderKeyDistributionPending(
    val messageId: String,
    val groupId: ByteArray,
    val senderIdentityPublicKey: ByteArray,
    val senderKeyId: Long,
    val recipientIdentityPublicKey: ByteArray,
)
