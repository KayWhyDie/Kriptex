package com.ivor.kriptex.deliverypolicy.group.senderkey.media

import com.ivor.kriptex.deliverypolicy.persistence.PersistedGroupMediaUnblockSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedGroupMediaUnblockState

/**
 * Receiver-side gating for group media completion.
 *
 * A mediaId is considered READY only when:
 * - all chunks have been verified, AND
 * - the sender-key wrapped media key has been applied.
 *
 * This is intentionally pure state: no IO, no timers, no background work.
 */
class GroupMediaUnblockCoordinator(
    private val onReady: (mediaId: String) -> Unit = {},
) {

    private data class GateState(
        val chunksVerified: Boolean,
        val mediaKeyAvailable: Boolean,
        val ready: Boolean,
    )

    private val byMediaId = LinkedHashMap<String, GateState>()

    @Synchronized
    fun markChunksVerified(mediaId: String): Boolean {
        require(mediaId.isNotEmpty()) { "empty_media_id" }
        val prev = byMediaId[mediaId] ?: GateState(chunksVerified = false, mediaKeyAvailable = false, ready = false)
        val next = advance(mediaId, prev.copy(chunksVerified = true))
        return next.ready && !prev.ready
    }

    @Synchronized
    fun markMediaKeyAvailable(mediaId: String): Boolean {
        require(mediaId.isNotEmpty()) { "empty_media_id" }
        val prev = byMediaId[mediaId] ?: GateState(chunksVerified = false, mediaKeyAvailable = false, ready = false)
        val next = advance(mediaId, prev.copy(mediaKeyAvailable = true))
        return next.ready && !prev.ready
    }

    @Synchronized
    fun isReady(mediaId: String): Boolean = byMediaId[mediaId]?.ready == true

    @Synchronized
    fun snapshot(capturedAtElapsedMs: Long): PersistedGroupMediaUnblockSnapshot {
        val states = byMediaId.entries.map { (mediaId, st) ->
            PersistedGroupMediaUnblockState(
                mediaId = mediaId,
                chunksVerified = st.chunksVerified,
                mediaKeyAvailable = st.mediaKeyAvailable,
                ready = st.ready,
            )
        }
        return PersistedGroupMediaUnblockSnapshot(capturedAtElapsedMs = capturedAtElapsedMs, states = states)
    }

    @Synchronized
    fun restore(snapshot: PersistedGroupMediaUnblockSnapshot) {
        byMediaId.clear()
        snapshot.states.forEach { st ->
            if (st.mediaId.isEmpty()) return@forEach
            byMediaId[st.mediaId] = GateState(
                chunksVerified = st.chunksVerified,
                mediaKeyAvailable = st.mediaKeyAvailable,
                ready = st.ready,
            )
        }
    }

    private fun advance(mediaId: String, candidate: GateState): GateState {
        val nowReady = candidate.chunksVerified && candidate.mediaKeyAvailable
        val next = candidate.copy(ready = candidate.ready || nowReady)
        byMediaId[mediaId] = next
        if (!candidate.ready && next.ready) {
            onReady(mediaId)
        }
        return next
    }
}
