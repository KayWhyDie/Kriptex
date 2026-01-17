package com.ivor.kriptex.deliverypolicy.persistence

/**
 * Persistable snapshot of group membership state.
 *
 * Safety:
 * - No secrets
 */
data class PersistedGroupStoreSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val groups: List<PersistedGroupStoreState>,
)

data class PersistedGroupStoreState(
    val conversationId: String,
    val groupId: ByteArray,
    val memberIdentityPublicKeys: List<ByteArray>,
)
