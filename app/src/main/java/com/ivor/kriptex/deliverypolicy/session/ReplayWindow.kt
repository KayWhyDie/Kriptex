package com.ivor.kriptex.deliverypolicy.session

/**
 * Sliding replay window for monotonically increasing sequence numbers.
 *
 * Window size is fixed (64) for simplicity and determinism.
 */
data class ReplayWindow(
    val highestSeqSeen: Long,
    /** Bit i means (highestSeqSeen - i) has been seen. i=0 is highest. */
    val seenBitmask: Long,
) {

    fun accept(seq: Long): ReplayDecision {
        if (seq <= 0) return ReplayDecision.Rejected("non_positive_seq")

        if (highestSeqSeen == 0L) {
            return ReplayDecision.Accepted(ReplayWindow(highestSeqSeen = seq, seenBitmask = 1L))
        }

        return when {
            seq > highestSeqSeen -> {
                val shift = (seq - highestSeqSeen).toInt()
                val shifted = if (shift >= 64) 0L else (seenBitmask shl shift)
                val newMask = shifted or 1L
                ReplayDecision.Accepted(ReplayWindow(highestSeqSeen = seq, seenBitmask = newMask))
            }

            else -> {
                val delta = (highestSeqSeen - seq).toInt()
                if (delta >= 64) {
                    ReplayDecision.Rejected("too_old")
                } else {
                    val bit = 1L shl delta
                    if ((seenBitmask and bit) != 0L) {
                        ReplayDecision.Rejected("replay")
                    } else {
                        ReplayDecision.Accepted(copy(seenBitmask = seenBitmask or bit))
                    }
                }
            }
        }
    }

    companion object {
        fun empty(): ReplayWindow = ReplayWindow(highestSeqSeen = 0L, seenBitmask = 0L)
    }
}

sealed interface ReplayDecision {
    data class Accepted(val updated: ReplayWindow) : ReplayDecision
    data class Rejected(val reason: String) : ReplayDecision
}
