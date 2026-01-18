package com.ivor.kriptex.deliverypolicy.persistence

/**
 * Persistable snapshot of Phase 4 group media key material.
 *
 * Notes:
 * - Media keys are sensitive and should only be stored encrypted-at-rest by the app.
 *   This deliverypolicy layer treats them as opaque bytes.
 * - No automatic resend/replay on restore.
 */
data class PersistedGroupMediaKeyStoreSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val entries: List<PersistedGroupMediaKeyEntry>,
)

data class PersistedGroupMediaKeyEntry(
    val mediaId: String,
    val groupId: ByteArray,
    val senderIdentityPublicKey: ByteArray,
    val senderKeyId: Long,
    val counter: Long,
    val mediaKey: ByteArray,
)
