package com.ivor.kriptex.deliverypolicy.persistence

/**
 * Persistable snapshot of the conversation-level delivery ledger.
 *
 * Pure state only:
 * - No runtime-only session state
 * - No delivery attempts
 * - No timers
 */
data class PersistedConversationDeliveryLedgerSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val entries: List<PersistedLedgerEntry>,
)

data class PersistedLedgerEntry(
    val messageId: String,
    val conversationId: String,
    val index: Int,
    val state: PersistedLedgerState,
    val terminalFailureReason: String? = null,
)

enum class PersistedLedgerState {
    QUEUED,
    SENT,
    RECEIVED,
    ACKED,
    FAILED_TERMINAL,
}
