package com.ivor.kriptex.deliverypolicy.persistence

/**
 * Persistable snapshot for Phase 4 receiver-side gating.
 *
 * Tracks which mediaIds are safe to assemble/decrypt.
 */
data class PersistedGroupMediaUnblockSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val states: List<PersistedGroupMediaUnblockState>,
)

data class PersistedGroupMediaUnblockState(
    val mediaId: String,
    val chunksVerified: Boolean,
    val mediaKeyAvailable: Boolean,
    val ready: Boolean,
)
