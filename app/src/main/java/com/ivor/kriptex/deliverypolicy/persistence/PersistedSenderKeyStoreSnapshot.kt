package com.ivor.kriptex.deliverypolicy.persistence

/**
 * Persistable snapshot of sender-key ratchet state.
 *
 * Safety: contains symmetric chain keys; must be protected by the app's local storage guarantees.
 */
data class PersistedSenderKeyStoreSnapshot(
    val version: Int = 2,
    val capturedAtElapsedMs: Long,
    val states: List<PersistedSenderKeyState>,
)

data class PersistedSenderKeyState(
    val groupId: ByteArray,
    val senderIdentityPublicKey: ByteArray,
    val senderKeyId: Long,
    val chainKey: ByteArray,
    val nextCounter: Long,
    val skippedMessageKeys: List<PersistedSkippedSenderMessageKey> = emptyList(),
)

data class PersistedSkippedSenderMessageKey(
    val counter: Long,
    val messageKey: ByteArray,
)
