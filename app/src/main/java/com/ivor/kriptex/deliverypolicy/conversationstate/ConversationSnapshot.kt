package com.ivor.kriptex.deliverypolicy.conversationstate

/**
 * Single derived, authoritative view of a conversation's state.
 *
 * Side-effect-free: computed from existing subsystems (store/ledger/connection/group key state).
 */
data class ConversationSnapshot(
    val conversationId: String,
    val conversationType: ConversationType,
    val health: ConversationHealth,
    val encryptionStatus: ConversationEncryptionStatus,
    val pendingMessageCount: Int,
    /** Monotonic elapsed time (ms) of last observed activity, or 0L if unknown. */
    val lastActivityTimestamp: Long,
)

enum class ConversationType {
    ONE_TO_ONE,
    GROUP,
}

enum class ConversationHealth {
    ACTIVE,
    DEGRADED,
    OFFLINE,
}

enum class ConversationEncryptionStatus {
    OK,
    MISSING_KEYS,
    SESSION_INVALID,
}
